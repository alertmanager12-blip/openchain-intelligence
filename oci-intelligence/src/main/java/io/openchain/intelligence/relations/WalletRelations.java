package io.openchain.intelligence.relations;

import io.openchain.core.model.Address;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.trace.Trace;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Relations between wallets (phase 2, roadmap "Graph intelligence"): inferences, never identity. Each relation is
 * {@code INFERRED} (ADR-0023 amendment 1), carries a confidence, the transfers it rests on and its wording ("may be
 * related", not "same owner"). Pure and deterministic; reads flows and labels only through {@link Trace.Source}.
 * <ul>
 * <li>{@code COMMON_FUNDER}: the subject and another wallet received funds from the same sender within
 *     {@code fundingWindow}; a sender that funded more than {@code maxFundedInWindow} wallets in that window is a
 *     distributor (payroll, airdrop) and creates no relation.</li>
 * <li>{@code COMMON_SINK}: both send at least {@code sinkShare} of their outflow of one token to the same address.</li>
 * <li>{@code SHARED_COUNTERPARTIES}: both sent to at least {@code minShared} of the same addresses; confidence follows
 *     the overlap of their recipient sets (Jaccard).</li>
 * </ul>
 * False links are the main risk (a legal one): an address labelled as a service (exchange, bridge, protocol, other), or
 * with more than {@code maxFlowsPerAddress} flows (probably a service), never links two wallets; dust and unverified
 * tokens are ignored; candidate lists are bounded.
 */
public final class WalletRelations {
    private WalletRelations() {}

    public enum Type { COMMON_FUNDER, COMMON_SINK, SHARED_COUNTERPARTIES }

    public record Config(Duration fundingWindow, int maxFundedInWindow, double sinkShare, int minShared, int maxFlowsPerAddress,
                         int maxCandidates, Map<String, BigDecimal> dust) {
        public static Config defaults(Map<String, BigDecimal> dust) {
            return new Config(Duration.ofHours(24), 20, 0.5, 3, 5_000, 200, dust);
        }
    }

    /**
     * @param via the shared funder (COMMON_FUNDER) or destination (COMMON_SINK); null for SHARED_COUNTERPARTIES
     */
    public record Relation(Address other, Type type, String level, double confidence, Address via, List<String> eventIds,
                           String explanation) {}

    public record Result(Address subject, List<Relation> relations, List<String> limits) {}

    static final String DISCLAIMER = "An inference from on-chain flows: these wallets may be related; it is not proof of common ownership or control.";
    static final int MAX_EVIDENCE = 20;

    public static Result find(Address subject, Trace.Source src, Config cfg) {
        Ctx c = new Ctx(src, cfg);
        Map<String, Relation> best = new TreeMap<>();
        if (!c.service(subject)) {
            commonFunder(subject, c, best);
            commonSink(subject, c, best);
            sharedCounterparties(subject, c, best);
        }
        List<Relation> out = new ArrayList<>(best.values());
        out.sort(Comparator.comparingDouble(Relation::confidence).reversed().thenComparing(r -> r.other().value())
                .thenComparing(r -> r.type().name()));
        List<String> limits = new ArrayList<>(List.of(DISCLAIMER,
                "Based on the flows held (bounded working set and loaded address histories): relations outside them are not seen."));
        if (c.truncated) limits.add("Candidate lists were bounded (" + cfg.maxCandidates() + "): some relations may be missing.");
        return new Result(subject, List.copyOf(out), List.copyOf(limits));
    }

    // ---- context ---------------------------------------------------------------------------------------------------

    private static final class Ctx {
        final Trace.Source src;
        final Config cfg;
        final Map<Address, Boolean> services = new HashMap<>();
        boolean truncated;
        Ctx(Trace.Source src, Config cfg) { this.src = src; this.cfg = cfg; }

        /** A service never links two wallets: labelled exchange, bridge, protocol or other, or too many flows. */
        boolean service(Address a) {
            return services.computeIfAbsent(a, x -> {
                for (AddressLabel l : src.labels(x)) if (!l.category().risky()) return true;
                return src.incoming(x).size() + src.outgoing(x).size() > cfg.maxFlowsPerAddress();
            });
        }

        boolean usable(ValueFlow f) {
            if (f.from().equals(f.to()) || f.token().isUnverified()) return false;
            BigDecimal t = cfg.dust().get(f.token().symbol());
            return t == null || f.amount().compareTo(t) >= 0;
        }

        List<ValueFlow> in(Address a) { return dedup(src.incoming(a).stream().filter(f -> f.to().equals(a) && usable(f)).toList()); }
        List<ValueFlow> out(Address a) { return dedup(src.outgoing(a).stream().filter(f -> f.from().equals(a) && usable(f)).toList()); }

        static List<ValueFlow> dedup(List<ValueFlow> l) {
            Map<String, ValueFlow> m = new TreeMap<>();
            for (ValueFlow f : l) m.putIfAbsent(f.eventId(), f);
            List<ValueFlow> out = new ArrayList<>(m.values());
            out.sort(Comparator.comparing(ValueFlow::timestamp).thenComparing(ValueFlow::eventId));
            return out;
        }
    }

    private static void keep(Map<String, Relation> best, Relation r) {
        best.merge(r.other().value() + "|" + r.type(), r, (a, b) -> b.confidence() > a.confidence() ? b : a);
    }

    private static double round(double v) { return Math.round(Math.min(0.95, Math.max(0, v)) * 100) / 100.0; }

    private static List<String> ids(List<ValueFlow> fs) {
        return fs.stream().map(ValueFlow::eventId).distinct().limit(MAX_EVIDENCE).toList();
    }

    // ---- common funder ---------------------------------------------------------------------------------------------

    private static void commonFunder(Address a, Ctx c, Map<String, Relation> best) {
        Map<Address, List<ValueFlow>> bySender = new TreeMap<>(Comparator.comparing(Address::value));
        for (ValueFlow f : c.in(a)) bySender.computeIfAbsent(f.from(), k -> new ArrayList<>()).add(f);
        int funders = 0;
        for (var en : bySender.entrySet()) {
            Address funder = en.getKey();
            if (c.service(funder)) continue;
            if (++funders > c.cfg.maxCandidates()) { c.truncated = true; break; }
            List<ValueFlow> funderOut = c.out(funder);
            for (ValueFlow toA : en.getValue()) {
                List<ValueFlow> near = funderOut.stream().filter(g -> !g.to().equals(a)
                        && Duration.between(toA.timestamp(), g.timestamp()).abs().compareTo(c.cfg.fundingWindow()) <= 0).toList();
                long funded = near.stream().map(ValueFlow::to).distinct().count();
                if (funded == 0 || funded > c.cfg.maxFundedInWindow()) continue; // nobody else, or a distributor
                for (ValueFlow g : near) {
                    if (c.service(g.to())) continue;
                    double gap = Duration.between(toA.timestamp(), g.timestamp()).abs().toSeconds()
                            / (double) Math.max(1, c.cfg.fundingWindow().toSeconds());
                    // closer in time and from a funder of few wallets: stronger; never above 0.8 for a single funder
                    double conf = 0.4 + 0.25 * (1 - gap) + 0.15 * (1 - (funded - 1) / (double) c.cfg.maxFundedInWindow());
                    keep(best, new Relation(g.to(), Type.COMMON_FUNDER, "INFERRED", round(Math.min(0.8, conf)), funder, ids(List.of(toA, g)),
                            "Both received funds from " + funder.value() + " within " + human(Duration.between(toA.timestamp(), g.timestamp()).abs())
                                    + " (it funded " + funded + " other wallet(s) in that window). " + DISCLAIMER));
                }
            }
        }
    }

    // ---- common sink -----------------------------------------------------------------------------------------------

    /** Share of an address's outflow of one token sent to each destination. */
    private static Map<String, Map<Address, Double>> sinkShares(List<ValueFlow> out) {
        Map<String, BigInteger> total = new HashMap<>();
        Map<String, Map<Address, BigInteger>> to = new TreeMap<>();
        for (ValueFlow f : out) {
            String k = Trace.tokenKey(f.token());
            total.merge(k, f.rawAmount(), BigInteger::add);
            to.computeIfAbsent(k, x -> new TreeMap<>(Comparator.comparing(Address::value))).merge(f.to(), f.rawAmount(), BigInteger::add);
        }
        Map<String, Map<Address, Double>> share = new TreeMap<>();
        to.forEach((k, m) -> {
            Map<Address, Double> s = new TreeMap<>(Comparator.comparing(Address::value));
            m.forEach((d, v) -> s.put(d, new BigDecimal(v).divide(new BigDecimal(total.get(k)), java.math.MathContext.DECIMAL64).doubleValue()));
            share.put(k, s);
        });
        return share;
    }

    private static void commonSink(Address a, Ctx c, Map<String, Relation> best) {
        List<ValueFlow> aOut = c.out(a);
        for (var tk : sinkShares(aOut).entrySet()) {
            for (var sink : tk.getValue().entrySet()) {
                if (sink.getValue() < c.cfg.sinkShare() || c.service(sink.getKey())) continue;
                Set<Address> senders = new LinkedHashSet<>();
                for (ValueFlow f : c.in(sink.getKey())) {
                    if (Trace.tokenKey(f.token()).equals(tk.getKey()) && !f.from().equals(a)) senders.add(f.from());
                }
                int n = 0;
                for (Address b : senders) {
                    if (c.service(b)) continue;
                    if (++n > c.cfg.maxCandidates()) { c.truncated = true; break; }
                    List<ValueFlow> bOut = c.out(b);
                    Double bShare = sinkShares(bOut).getOrDefault(tk.getKey(), Map.of()).get(sink.getKey());
                    if (bShare == null || bShare < c.cfg.sinkShare()) continue;
                    List<ValueFlow> ev = new ArrayList<>();
                    aOut.stream().filter(f -> f.to().equals(sink.getKey()) && Trace.tokenKey(f.token()).equals(tk.getKey())).limit(MAX_EVIDENCE / 2).forEach(ev::add);
                    bOut.stream().filter(f -> f.to().equals(sink.getKey()) && Trace.tokenKey(f.token()).equals(tk.getKey())).limit(MAX_EVIDENCE / 2).forEach(ev::add);
                    double conf = 0.4 + 0.4 * Math.min(sink.getValue(), bShare);
                    keep(best, new Relation(b, Type.COMMON_SINK, "INFERRED", round(Math.min(0.8, conf)), sink.getKey(), ids(ev),
                            String.format(java.util.Locale.ROOT, "Both send most of their outflow of this token to %s (%.0f %% and %.0f %%). ",
                                    sink.getKey().value(), 100 * sink.getValue(), 100 * bShare) + DISCLAIMER));
                }
            }
        }
    }

    // ---- shared counterparties -------------------------------------------------------------------------------------

    private static void sharedCounterparties(Address a, Ctx c, Map<String, Relation> best) {
        List<ValueFlow> aOut = c.out(a);
        Set<Address> aTo = new TreeSet<>(Comparator.comparing(Address::value));
        for (ValueFlow f : aOut) if (!c.service(f.to())) aTo.add(f.to());
        if (aTo.size() < c.cfg.minShared()) return;
        Map<Address, Set<Address>> shared = new TreeMap<>(Comparator.comparing(Address::value));
        int read = 0;
        for (Address r : aTo) {
            if (++read > c.cfg.maxCandidates()) { c.truncated = true; break; }
            for (ValueFlow f : c.in(r)) {
                if (f.from().equals(a) || c.service(f.from())) continue;
                shared.computeIfAbsent(f.from(), k -> new TreeSet<>(Comparator.comparing(Address::value))).add(r);
            }
        }
        for (var en : shared.entrySet()) {
            if (en.getValue().size() < c.cfg.minShared()) continue;
            Address b = en.getKey();
            List<ValueFlow> bOut = c.out(b);
            Set<Address> bTo = new TreeSet<>(Comparator.comparing(Address::value));
            for (ValueFlow f : bOut) if (!c.service(f.to())) bTo.add(f.to());
            Set<Address> union = new TreeSet<>(Comparator.comparing(Address::value));
            union.addAll(aTo);
            union.addAll(bTo);
            double jaccard = en.getValue().size() / (double) union.size();
            List<ValueFlow> ev = new ArrayList<>();
            for (Address r : en.getValue()) {
                aOut.stream().filter(f -> f.to().equals(r)).findFirst().ifPresent(ev::add);
                bOut.stream().filter(f -> f.to().equals(r)).findFirst().ifPresent(ev::add);
            }
            keep(best, new Relation(b, Type.SHARED_COUNTERPARTIES, "INFERRED", round(Math.min(0.8, 0.2 + 0.6 * jaccard)), null, ids(ev),
                    String.format(java.util.Locale.ROOT, "Both sent to the same %d address(es) (%.0f %% of their recipients together). ",
                            en.getValue().size(), 100 * jaccard) + DISCLAIMER));
        }
    }

    private static String human(Duration d) {
        long m = d.toMinutes();
        return m < 60 ? m + " min" : m < 1440 ? (m / 60) + " h" : (m / 1440) + " day(s)";
    }
}
