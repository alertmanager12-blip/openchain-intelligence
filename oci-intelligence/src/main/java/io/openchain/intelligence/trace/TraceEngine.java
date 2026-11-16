package io.openchain.intelligence.trace;

import io.openchain.core.model.Address;
import io.openchain.intelligence.flow.TransferIdentity;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.trace.Trace.Attribution;
import io.openchain.intelligence.trace.Trace.Direction;
import io.openchain.intelligence.trace.Trace.Edge;
import io.openchain.intelligence.trace.Trace.Method;
import io.openchain.intelligence.trace.Trace.Node;
import io.openchain.intelligence.trace.Trace.Request;
import io.openchain.intelligence.trace.Trace.Stop;
import io.openchain.intelligence.trace.Trace.StopReason;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Fund flow trace engine (Fund Flow Trace P0-D, first part). Pure and deterministic: no clock, no I/O beyond
 * {@link Trace.Source}, sorted outputs.
 * <ol>
 * <li><b>Normalisation</b>: flows outside the window or of another network are dropped, a flow given twice counts once,
 *     and a transfer seen by two sources (TRC-20 live and TronGrid history) is merged: one value, both ids kept.</li>
 * <li><b>Pools</b>: for each address and token, flows are replayed in time order (inflows before outflows at the same
 *     instant; flagged {@code SAME_TIME_ORDER}) from an opening balance. Each outflow takes either the composition of the
 *     pool at that instant ({@code PROPORTIONAL}) or its oldest lots ({@code FIFO}), in exact fractions. Tokens are never
 *     mixed. An unknown opening balance is taken at its smallest value consistent with the flows for the upper bound;
 *     the lower bound is 0 (a large earlier balance could have left first).</li>
 * <li><b>Propagation by rounds</b>: FORWARD, the value each flow carries from the seed; BACKWARD, the value of the seed's
 *     receipts each upstream flow accounts for. Repeated until nothing changes (at most depth + 2 rounds).</li>
 * <li><b>Stops</b>, always with a reason: depth, nodes, flows read, stop category (exchange, bridge), share below the
 *     minimum, dust, unverified token, coverage gap, return to the seed, unknown opening balance, same-instant order.</li>
 * </ol>
 * The result is {@code COMPLETE} only when every stop is a decision (category, share, dust, unverified token), never
 * missing data.
 */
public final class TraceEngine {
    private static final String OPENING = "\u0000opening";

    private final Request req;
    private final Trace.Source source;
    private final boolean forward;

    private final Map<Address, Integer> depth = new LinkedHashMap<>();
    /** The address through which each address was first reached (closer to the seed): back edges are recognised with it. */
    private final Map<Address, Address> parent = new HashMap<>();
    private final Map<Address, Loaded> loaded = new HashMap<>();
    private final Set<Address> partialNodes = new HashSet<>();
    private final Map<String, Pool> pools = new HashMap<>(); // address|tokenKey
    private final Map<String, Stop> stops = new TreeMap<>();
    private final Map<String, ValueFlow> flows = new HashMap<>(); // eventId -> flow (normalised)
    private final Map<String, List<String>> aliases = new HashMap<>();
    private int flowsRead;
    private boolean readLimitHit;

    private TraceEngine(Request req, Trace.Source source) {
        this.req = Objects.requireNonNull(req);
        this.source = Objects.requireNonNull(source);
        this.forward = req.direction() == Direction.FORWARD;
    }

    public static Trace.Result run(Request req, Trace.Source source) { return new TraceEngine(req, source).run(); }

    // ---- loading and normalisation --------------------------------------------------------------------------------

    private record Loaded(List<ValueFlow> in, List<ValueFlow> out) {}

    /** Flows of {@code a}, normalised; null when the flows-read budget does not allow loading it. */
    private Loaded load(Address a) {
        Loaded l = loaded.get(a);
        if (l != null) return l;
        List<ValueFlow> rawIn = source.incoming(a), rawOut = source.outgoing(a);
        if (!a.equals(req.seed()) && rawIn.size() + rawOut.size() > req.limits().maxFlowsPerAddress()) {
            stop(a, null, StopReason.SERVICE_LIKE, (rawIn.size() + rawOut.size()) + " flows (more than " + req.limits().maxFlowsPerAddress()
                    + "): probably a service; not expanded");
            return null;
        }
        if (flowsRead + rawIn.size() + rawOut.size() > req.limits().maxFlowsRead()) {
            readLimitHit = true;
            stop(a, null, StopReason.FLOWS_READ, "loading this address would exceed " + req.limits().maxFlowsRead() + " flows read");
            return null;
        }
        flowsRead += rawIn.size() + rawOut.size();
        List<ValueFlow> all = new ArrayList<>(rawIn);
        all.addAll(rawOut);
        all.sort(Comparator.comparing(ValueFlow::eventId)); // deterministic whatever the source order
        Map<String, ValueFlow> unique = new LinkedHashMap<>();
        for (ValueFlow f : all) {
            if (f.network() != req.network() || f.from().equals(f.to()) || !req.window().contains(f.timestamp())) continue;
            if (!f.from().equals(a) && !f.to().equals(a)) continue;
            unique.putIfAbsent(f.eventId(), f);
        }
        // a transfer seen live and by a history without log index: keep the live flow, remember the other id
        List<ValueFlow> kept = new ArrayList<>();
        for (ValueFlow f : unique.values()) {
            if (TransferIdentity.historyRank(f).isPresent()) {
                var twin = TransferIdentity.counterpartIn(f, unique.values());
                if (twin.isPresent()) {
                    List<String> al = aliases.computeIfAbsent(twin.get().eventId(), k -> new ArrayList<>());
                    if (!al.contains(f.eventId())) al.add(f.eventId());
                    continue;
                }
            }
            kept.add(f);
        }
        for (ValueFlow f : kept) flows.putIfAbsent(f.eventId(), f);
        l = new Loaded(kept.stream().filter(f -> f.to().equals(a)).toList(), kept.stream().filter(f -> f.from().equals(a)).toList());
        loaded.put(a, l);
        if (!source.complete(a, req.window())) {
            partialNodes.add(a);
            stop(a, null, StopReason.COVERAGE_GAP, "not every flow of this address in the window is known");
        }
        return l;
    }

    // ---- pools --------------------------------------------------------------------------------------------------

    /** Composition of each outflow of one address in one token: origin (inflow eventId or opening) -> raw amount. */
    private record Pool(Map<String, Map<String, Fraction>> composition, boolean unknownOpening) {}

    private Pool pool(Address a, String tokenKey, Loaded l) {
        return pools.computeIfAbsent(a.value() + "|" + tokenKey, k -> buildPool(a, tokenKey, l));
    }

    private Pool buildPool(Address a, String tokenKey, Loaded l) {
        record Ev(ValueFlow f, boolean in) {}
        List<Ev> evs = new ArrayList<>();
        for (ValueFlow f : l.in()) if (Trace.tokenKey(f.token()).equals(tokenKey)) evs.add(new Ev(f, true));
        for (ValueFlow f : l.out()) if (Trace.tokenKey(f.token()).equals(tokenKey)) evs.add(new Ev(f, false));
        evs.sort(Comparator.comparing((Ev e) -> e.f().timestamp()).thenComparing(e -> e.in() ? 0 : 1)
                .thenComparing(e -> TransferIdentity.liveLogIndex(e.f()).orElse(Integer.MAX_VALUE)).thenComparing(e -> e.f().eventId()));
        // same instant in and out (other transactions): their real order is unknown without the log index
        Map<java.time.Instant, int[]> perInstant = new HashMap<>();
        for (Ev e : evs) perInstant.computeIfAbsent(e.f().timestamp(), k -> new int[2])[e.in() ? 0 : 1]++;
        if (perInstant.values().stream().anyMatch(c -> c[0] > 0 && c[1] > 0)) {
            stop(a, tokenKey, StopReason.SAME_TIME_ORDER, "inflows and outflows at the same instant: their order is assumed (inflows first)");
        }
        BigInteger running = BigInteger.ZERO, lowest = BigInteger.ZERO;
        for (Ev e : evs) {
            running = e.in() ? running.add(e.f().rawAmount()) : running.subtract(e.f().rawAmount());
            if (running.compareTo(lowest) < 0) lowest = running;
        }
        BigInteger implied = lowest.negate();
        var known = source.openingBalance(a, tokenKey, req.window());
        boolean unknown = known.isEmpty();
        BigInteger opening = implied;
        if (known.isPresent()) {
            opening = known.get().max(implied);
            if (implied.compareTo(known.get()) > 0) {
                partialNodes.add(a);
                stop(a, tokenKey, StopReason.COVERAGE_GAP, "the flows spend more than the given opening balance: some inflows are missing");
            }
        } else if (evs.stream().anyMatch(e -> !e.in())) {
            stop(a, tokenKey, StopReason.UNKNOWN_OPENING_BALANCE, "balance before the window unknown: lower bound 0");
        }

        Map<String, Map<String, Fraction>> comp = new HashMap<>();
        if (req.method() == Method.PROPORTIONAL) {
            Map<String, Fraction> lots = new LinkedHashMap<>();
            Fraction total = Fraction.of(opening);
            if (opening.signum() > 0) lots.put(OPENING, Fraction.of(opening));
            for (Ev e : evs) {
                Fraction amt = Fraction.of(e.f().rawAmount());
                if (e.in()) {
                    lots.merge(e.f().eventId(), amt, Fraction::plus);
                    total = total.plus(amt);
                    continue;
                }
                Map<String, Fraction> c = new LinkedHashMap<>();
                if (!total.isZero()) {
                    Fraction share = amt.dividedBy(total);
                    for (var it = lots.entrySet().iterator(); it.hasNext(); ) {
                        var lot = it.next();
                        Fraction take = lot.getValue().times(share);
                        if (take.isZero()) continue;
                        c.put(lot.getKey(), take);
                        lot.setValue(lot.getValue().minus(take));
                        if (lot.getValue().isZero()) it.remove();
                    }
                    total = total.minus(amt);
                }
                comp.put(e.f().eventId(), c);
            }
        } else {
            Deque<Object[]> lots = new ArrayDeque<>(); // {origin, Fraction remaining}
            if (opening.signum() > 0) lots.add(new Object[]{OPENING, Fraction.of(opening)});
            for (Ev e : evs) {
                Fraction amt = Fraction.of(e.f().rawAmount());
                if (e.in()) { lots.add(new Object[]{e.f().eventId(), amt}); continue; }
                Map<String, Fraction> c = new LinkedHashMap<>();
                Fraction need = amt;
                while (!need.isZero() && !lots.isEmpty()) {
                    Object[] lot = lots.peekFirst();
                    Fraction have = (Fraction) lot[1], take = have.min(need);
                    c.merge((String) lot[0], take, Fraction::plus);
                    need = need.minus(take);
                    if (take.compareTo(have) == 0) lots.pollFirst(); else lot[1] = have.minus(take);
                }
                comp.put(e.f().eventId(), c);
            }
        }
        return new Pool(comp, unknown);
    }

    // ---- propagation --------------------------------------------------------------------------------------------

    /** Traced value of a flow (raw units): FORWARD from the seed, BACKWARD toward the seed's receipts. */
    private record Traced(Fraction low, Fraction high, boolean partial) {
        static final Traced NONE = new Traced(Fraction.ZERO, Fraction.ZERO, false);
        Traced plus(Traced o) { return new Traced(low.plus(o.low), high.plus(o.high), partial || o.partial); }
        Traced scaled(Fraction k) { return new Traced(low.times(k), high.times(k), partial); }
    }

    private Trace.Result run() {
        depth.put(req.seed(), 0);
        Loaded seed = load(req.seed());
        Map<String, Traced> traced = new TreeMap<>();
        int rounds = 0;
        if (seed != null) {
            boolean changed = true;
            while (changed && rounds < req.limits().maxDepth() + 2) {
                rounds++;
                Map<String, Traced> next = forward ? forwardRound(traced) : backwardRound(traced);
                changed = !next.equals(traced);
                traced = next;
            }
        }
        return result(traced, rounds);
    }

    private boolean follows(ValueFlow f) {
        String key = Trace.tokenKey(f.token());
        if (req.tokenKey() != null && !req.tokenKey().equals(key)) return false;
        if (f.token().isUnverified()) {
            stop(forward ? f.to() : f.from(), key, StopReason.UNVERIFIED_TOKEN, "flow " + f.eventId() + " of an unverified token contract not followed");
            return false;
        }
        BigDecimal threshold = req.dust().get(f.token().symbol());
        if (threshold != null && f.amount().compareTo(threshold) < 0) {
            stop(forward ? f.to() : f.from(), key, StopReason.DUST, "flow " + f.eventId() + " below the dust threshold not followed");
            return false;
        }
        return true;
    }

    /** May the trace continue through {@code a} (one more hop)? Records why not. */
    private boolean expandable(Address a) {
        int d = depth.get(a);
        if (d > 0) {
            for (AddressLabel l : source.labels(a)) {
                if (req.stopAt().contains(l.category())) {
                    stop(a, null, StopReason.STOP_CATEGORY, "listed as " + l.category() + " by " + l.source());
                    return false;
                }
            }
        }
        return true;
    }

    /** Adds a node reached at {@code d}; false when the node budget is exhausted. */
    private boolean reach(Address from, Address a, int d) {
        if (depth.containsKey(a)) return true;
        if (depth.size() >= req.limits().maxNodes()) {
            stop(from, null, StopReason.NODES, "node budget " + req.limits().maxNodes() + " reached");
            return false;
        }
        depth.put(a, d);
        parent.put(a, from);
        return true;
    }

    /** True when {@code x} is {@code a} or one of the addresses the trace went through to reach {@code a}. */
    private boolean onPathTo(Address x, Address a) {
        for (Address c = a; c != null; c = parent.get(c)) if (c.equals(x)) return true;
        return false;
    }

    /** A flow that brings traced value back to an address the trace already went through on the way: circulation. */
    private boolean backEdge(Edge e) {
        Address near = forward ? e.from() : e.to(), far = forward ? e.to() : e.from();
        return depth.containsKey(near) && onPathTo(far, near);
    }

    /** One FORWARD round from the values of the previous round. */
    private Map<String, Traced> forwardRound(Map<String, Traced> prev) {
        Map<String, Traced> next = new TreeMap<>();
        // the seed: everything it sends in the window comes from it
        Loaded s = load(req.seed());
        Fraction seedTotal = Fraction.ZERO;
        for (ValueFlow f : s.out()) {
            if (req.tokenKey() != null && !req.tokenKey().equals(Trace.tokenKey(f.token()))) continue;
            Fraction amt = Fraction.of(f.rawAmount());
            next.put(f.eventId(), new Traced(amt, amt, partialNodes.contains(req.seed())));
            seedTotal = seedTotal.plus(amt);
        }
        // every other reached node, in depth order
        List<Address> order = new ArrayList<>(depth.keySet());
        order.sort(Comparator.comparing((Address x) -> depth.get(x)).thenComparing(Address::value));
        for (Address a : order) {
            if (a.equals(req.seed())) continue;
            int d = depth.get(a);
            Loaded l = load(a);
            if (l == null) continue;
            Map<String, Traced> inTraced = new HashMap<>();
            for (ValueFlow f : l.in()) {
                Traced t = prev.get(f.eventId());
                if (t != null && t.high().signum() > 0) inTraced.put(f.eventId(), t);
            }
            if (inTraced.isEmpty() || !expandable(a)) continue;
            Set<String> tokens = new TreeSet<>();
            for (String id : inTraced.keySet()) tokens.add(Trace.tokenKey(flows.get(id).token()));
            for (String tk : tokens) {
                Pool p = pool(a, tk, l);
                for (ValueFlow o : l.out()) {
                    if (!Trace.tokenKey(o.token()).equals(tk)) continue;
                    Map<String, Fraction> c = p.composition().getOrDefault(o.eventId(), Map.of());
                    Traced t = Traced.NONE;
                    for (var e : c.entrySet()) {
                        Traced ti = inTraced.get(e.getKey());
                        if (ti == null) continue;
                        Fraction k = e.getValue().dividedBy(Fraction.of(flows.get(e.getKey()).rawAmount()));
                        t = t.plus(ti.scaled(k));
                    }
                    if (t.high().isZero()) continue;
                    if (p.unknownOpening()) t = new Traced(Fraction.ZERO, t.high(), t.partial());
                    if (partialNodes.contains(a)) t = new Traced(t.low(), t.high(), true);
                    if (d >= req.limits().maxDepth()) {
                        stop(a, tk, StopReason.DEPTH, "maximum depth " + req.limits().maxDepth() + " reached; outflows not followed");
                        break;
                    }
                    next.put(o.eventId(), t);
                }
            }
        }
        // reach the recipients of the traced flows
        for (var e : next.entrySet()) {
            ValueFlow f = flows.get(e.getKey());
            Address to = f.to();
            if (to.equals(req.seed())) {
                stop(to, Trace.tokenKey(f.token()), StopReason.CYCLE, "traced value returns to the seed (" + f.eventId() + "); not followed further");
                continue;
            }
            if (depth.containsKey(to)) continue;
            if (!follows(f)) continue;
            if (!seedTotal.isZero() && e.getValue().high().dividedBy(seedTotal).compareTo(req.minShare()) < 0) {
                stop(to, Trace.tokenKey(f.token()), StopReason.BELOW_MIN_SHARE, "flow " + f.eventId() + " carries less than the minimum share");
                continue;
            }
            reach(f.from(), to, depth.get(f.from()) + 1);
        }
        return next;
    }

    /** One BACKWARD round: value of the seed's receipts that each upstream flow accounts for. */
    private Map<String, Traced> backwardRound(Map<String, Traced> prev) {
        Map<String, Traced> next = new TreeMap<>();
        Loaded s = load(req.seed());
        for (ValueFlow f : s.in()) {
            if (req.tokenKey() != null && !req.tokenKey().equals(Trace.tokenKey(f.token()))) continue;
            Fraction amt = Fraction.of(f.rawAmount());
            next.put(f.eventId(), new Traced(amt, amt, partialNodes.contains(req.seed())));
        }
        List<Address> order = new ArrayList<>(depth.keySet());
        order.sort(Comparator.comparing((Address x) -> depth.get(x)).thenComparing(Address::value));
        for (Address a : order) {
            if (a.equals(req.seed())) continue;
            int d = depth.get(a);
            Loaded l = load(a);
            if (l == null) continue;
            Map<String, Traced> outTraced = new HashMap<>();
            for (ValueFlow f : l.out()) {
                Traced t = prev.get(f.eventId());
                if (t != null && t.high().signum() > 0) outTraced.put(f.eventId(), t);
            }
            if (outTraced.isEmpty() || !expandable(a)) continue;
            Set<String> tokens = new TreeSet<>();
            for (String id : outTraced.keySet()) tokens.add(Trace.tokenKey(flows.get(id).token()));
            for (String tk : tokens) {
                Pool p = pool(a, tk, l);
                Map<String, Traced> acc = new TreeMap<>();
                for (var oe : outTraced.entrySet()) {
                    ValueFlow o = flows.get(oe.getKey());
                    if (!Trace.tokenKey(o.token()).equals(tk)) continue;
                    Fraction k = Fraction.ONE.dividedBy(Fraction.of(o.rawAmount()));
                    for (var c : p.composition().getOrDefault(o.eventId(), Map.of()).entrySet()) {
                        if (c.getKey().equals(OPENING)) continue;
                        Traced t = oe.getValue().scaled(c.getValue().times(k));
                        if (p.unknownOpening()) t = new Traced(Fraction.ZERO, t.high(), t.partial());
                        if (partialNodes.contains(a)) t = new Traced(t.low(), t.high(), true);
                        acc.merge(c.getKey(), t, Traced::plus);
                    }
                }
                if (acc.isEmpty()) continue;
                if (d >= req.limits().maxDepth()) {
                    stop(a, tk, StopReason.DEPTH, "maximum depth " + req.limits().maxDepth() + " reached; sources not followed");
                    continue;
                }
                next.putAll(acc);
            }
        }
        Fraction seedTotal = Fraction.ZERO;
        for (ValueFlow f : s.in()) if (next.containsKey(f.eventId())) seedTotal = seedTotal.plus(Fraction.of(f.rawAmount()));
        for (var e : next.entrySet()) {
            ValueFlow f = flows.get(e.getKey());
            Address from = f.from();
            if (from.equals(req.seed())) {
                stop(from, Trace.tokenKey(f.token()), StopReason.CYCLE, "traced value comes from the seed itself (" + f.eventId() + ")");
                continue;
            }
            if (depth.containsKey(from)) continue;
            if (!follows(f)) continue;
            if (!seedTotal.isZero() && e.getValue().high().dividedBy(seedTotal).compareTo(req.minShare()) < 0) {
                stop(from, Trace.tokenKey(f.token()), StopReason.BELOW_MIN_SHARE, "flow " + f.eventId() + " carries less than the minimum share");
                continue;
            }
            reach(f.to(), from, depth.get(f.to()) + 1);
        }
        // flows into the seed that are dust or unverified: never followed
        next.keySet().removeIf(id -> {
            ValueFlow f = flows.get(id);
            return f.to().equals(req.seed()) && !followsQuiet(f);
        });
        return next;
    }

    private boolean followsQuiet(ValueFlow f) {
        if (f.token().isUnverified()) return false;
        BigDecimal threshold = req.dust().get(f.token().symbol());
        return threshold == null || f.amount().compareTo(threshold) >= 0;
    }

    // ---- result -------------------------------------------------------------------------------------------------

    private void stop(Address a, String tokenKey, StopReason r, String detail) {
        stops.putIfAbsent(r + "|" + a.value() + "|" + (tokenKey == null ? "" : tokenKey), new Stop(a, tokenKey, r, detail));
    }

    // ---- patterns (all INFERRED) -----------------------------------------------------------------------------------

    private List<Trace.Finding> findings(List<Edge> edges) {
        List<Trace.Finding> out = new ArrayList<>();
        var pt = req.patterns();
        List<Edge> ahead = new ArrayList<>();
        for (Edge e : edges) {
            if (backEdge(e)) {
                Address far = forward ? e.to() : e.from(), near = forward ? e.from() : e.to();
                List<Address> loop = new ArrayList<>();
                for (Address c = near; c != null; c = parent.get(c)) { loop.add(0, c); if (c.equals(far)) break; }
                out.add(new Trace.Finding(Trace.FindingType.CIRCULATION, "INFERRED", List.copyOf(loop), List.of(e.eventId()),
                        "traced value returns to " + far.value() + " after " + (loop.size() - 1) + " hop(s)"));
            } else {
                ahead.add(e);
            }
        }
        // dispersion (forward) / consolidation (backward): one address, many distinct counterparties of traced value
        Map<String, List<Edge>> byHub = new TreeMap<>();
        for (Edge e : ahead) byHub.computeIfAbsent((forward ? e.from() : e.to()).value() + "|" + e.tokenKey(), k -> new ArrayList<>()).add(e);
        for (List<Edge> group : byHub.values()) {
            Address hub = forward ? group.getFirst().from() : group.getFirst().to();
            long distinct = group.stream().map(e -> forward ? e.to() : e.from()).distinct().count();
            if (forward && distinct >= pt.minFanOut()) {
                out.add(new Trace.Finding(Trace.FindingType.DISPERSION, "INFERRED", List.of(hub), group.stream().map(Edge::eventId).toList(),
                        "traced value sent to " + distinct + " distinct addresses"));
            } else if (!forward && distinct >= pt.minFanIn()) {
                out.add(new Trace.Finding(Trace.FindingType.CONSOLIDATION, "INFERRED", List.of(hub), group.stream().map(Edge::eventId).toList(),
                        "traced value received from " + distinct + " distinct addresses"));
            }
        }
        if (forward) out.addAll(peelChains(ahead));
        return List.copyOf(out);
    }

    /**
     * Peel chain: consecutive addresses that each pass most of the value on to the next one and detach a small, regular
     * part elsewhere. A step: exactly two traced outflows of one token, the smaller at most {@code maxPeelShare} of both.
     */
    private List<Trace.Finding> peelChains(List<Edge> ahead) {
        var pt = req.patterns();
        Map<String, List<Edge>> outs = new TreeMap<>();
        for (Edge e : ahead) outs.computeIfAbsent(e.from().value() + "|" + e.tokenKey(), k -> new ArrayList<>()).add(e);
        Map<String, Edge[]> steps = new TreeMap<>(); // from|token -> {main, peel}
        for (var en : outs.entrySet()) {
            List<Edge> o = en.getValue();
            if (o.size() != 2) continue;
            Edge big = o.get(0).rawAmount().compareTo(o.get(1).rawAmount()) >= 0 ? o.get(0) : o.get(1);
            Edge small = big == o.get(0) ? o.get(1) : o.get(0);
            Fraction share = Fraction.of(small.rawAmount()).dividedBy(Fraction.of(small.rawAmount().add(big.rawAmount())));
            if (share.compareTo(pt.maxPeelShare()) <= 0) steps.put(en.getKey(), new Edge[]{big, small});
        }
        Set<String> continued = new HashSet<>();
        for (Edge[] st : steps.values()) continued.add(st[0].to().value() + "|" + st[0].tokenKey());
        List<Trace.Finding> out = new ArrayList<>();
        for (var en : steps.entrySet()) {
            if (continued.contains(en.getKey())) continue; // not the start of a chain
            List<Address> chain = new ArrayList<>();
            List<Edge> peels = new ArrayList<>(), mains = new ArrayList<>();
            String k = en.getKey();
            Set<String> seen = new HashSet<>();
            while (steps.containsKey(k) && seen.add(k)) {
                Edge[] st = steps.get(k);
                chain.add(st[0].from());
                mains.add(st[0]);
                peels.add(st[1]);
                k = st[0].to().value() + "|" + st[0].tokenKey();
            }
            if (peels.size() < pt.minPeels()) continue;
            BigInteger min = peels.stream().map(Edge::rawAmount).min(BigInteger::compareTo).orElseThrow();
            BigInteger max = peels.stream().map(Edge::rawAmount).max(BigInteger::compareTo).orElseThrow();
            if (min.signum() == 0 || new Fraction(max, min).compareTo(pt.maxPeelSpread()) > 0) continue; // irregular
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < peels.size(); i++) { ids.add(mains.get(i).eventId()); ids.add(peels.get(i).eventId()); }
            out.add(new Trace.Finding(Trace.FindingType.PEEL_CHAIN, "INFERRED", List.copyOf(chain), List.copyOf(ids),
                    peels.size() + " regular detachments (" + min + " to " + max + " raw units) along " + chain.size() + " addresses"));
        }
        return out;
    }

    /** Depth of a reached address, or one more than the address it got value from (not followed: dust, share, budget). */
    private int depthOf(Address a, List<Edge> edges) {
        Integer d = depth.get(a);
        if (d != null) return d;
        int best = Integer.MAX_VALUE;
        for (Edge e : edges) {
            Address other = forward ? e.from() : e.to(), here = forward ? e.to() : e.from();
            if (here.equals(a) && depth.containsKey(other)) best = Math.min(best, depth.get(other) + 1);
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    private Trace.Result result(Map<String, Traced> traced, int rounds) {
        List<Edge> edges = new ArrayList<>();
        Map<String, Traced> perAddress = new TreeMap<>(); // address|token -> traced
        for (var e : traced.entrySet()) {
            ValueFlow f = flows.get(e.getKey());
            Traced t = e.getValue();
            if (t.high().isZero()) continue;
            // a dust or unverified flow into the seed is not part of a backward trace
            if (!forward && f.to().equals(req.seed()) && !followsQuiet(f)) continue;
            Address at = forward ? f.to() : f.from();
            Edge edge = new Edge(f.eventId(), List.copyOf(aliases.getOrDefault(f.eventId(), List.of())), f.txHash(), f.timestamp(), f.from(), f.to(),
                    Trace.tokenKey(f.token()), f.rawAmount(), t.low(), t.high(), f.token());
            edges.add(edge);
            // value coming back to an address already on the way is not received a second time (circulation finding)
            if (!at.equals(req.seed()) && !backEdge(edge)) perAddress.merge(at.value() + "|" + Trace.tokenKey(f.token()), t, Traced::plus);
        }
        edges.sort(Comparator.comparing(Edge::timestamp).thenComparing(Edge::eventId));
        List<Attribution> attributions = new ArrayList<>();
        Map<String, Address> byValue = new HashMap<>();
        for (Address a : depth.keySet()) byValue.put(a.value(), a);
        for (Edge e : edges) byValue.putIfAbsent((forward ? e.to() : e.from()).value(), forward ? e.to() : e.from());
        for (var e : perAddress.entrySet()) {
            String[] k = e.getKey().split("\\|", 2);
            Address a = byValue.get(k[0]);
            attributions.add(new Attribution(a, k[1], depthOf(a, edges), e.getValue().low(), e.getValue().high(), e.getValue().partial()));
        }
        attributions.sort(Comparator.comparingInt(Attribution::depth).thenComparing(x -> x.address().value()).thenComparing(Attribution::tokenKey));
        List<Node> nodes = new ArrayList<>();
        for (var e : depth.entrySet()) {
            List<String> ls = source.labels(e.getKey()).stream().map(l -> l.category() + " (" + l.source() + ")").sorted().toList();
            nodes.add(new Node(e.getKey(), e.getValue(), ls));
        }
        nodes.sort(Comparator.comparingInt(Node::depth).thenComparing(n -> n.address().value()));
        List<Stop> st = new ArrayList<>(stops.values());
        boolean complete = st.stream().allMatch(s -> s.reason().benign()) && !readLimitHit;
        List<Trace.Finding> findings = findings(edges);
        for (Trace.Finding x : findings) {
            if (x.type() == Trace.FindingType.CIRCULATION && !x.addresses().isEmpty()) {
                stop(x.addresses().getFirst(), null, StopReason.CYCLE, "traced value comes back to this address (" + x.eventIds() + ")");
            }
        }
        st = new ArrayList<>(stops.values());
        complete = st.stream().allMatch(s -> s.reason().benign()) && !readLimitHit;
        return new Trace.Result(complete ? Trace.Status.COMPLETE : Trace.Status.PARTIAL, req.direction(), req.method(), List.copyOf(nodes),
                List.copyOf(edges), List.copyOf(attributions), List.copyOf(st), findings, new Trace.Counters(depth.size(), flowsRead, rounds),
                Trace.ENGINE_VERSION);
    }
}
