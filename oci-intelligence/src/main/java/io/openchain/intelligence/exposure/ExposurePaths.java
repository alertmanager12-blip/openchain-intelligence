package io.openchain.intelligence.exposure;

import io.openchain.core.model.Address;
import io.openchain.intelligence.detection.DetectionConfig;
import io.openchain.intelligence.flow.FlowStore;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.LabelRepository;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single qualification of exposure paths (Fund Flow Trace P0-A, {@code docs/FUND_FLOW_TRACE_P0_A_EXPOSURE.md}),
 * shared by the exposure detector and {@code GET /wallets/{a}/exposure} so that both answer the same question the same
 * way. Pure: reads the flow store and the labels, keeps no state.
 * <ul>
 * <li>Direct (1 hop, both directions) to an address listed in a risky category. A flow of an unverified token, or an
 *     inbound flow below the per-symbol dust threshold, creates no exposure (anyone can send one): it is kept apart in
 *     {@link Direct#ignored()}.</li>
 * <li>Indirect (2..maxHops, inbound value only): through actual, time-consistent flows (each upstream flow not later
 *     than the downstream one), stopping at the first listed address met upstream and at {@code stopAt} categories
 *     (exchange, bridge). Budgets: {@code maxVisitedFlows}, {@code maxPaths}.</li>
 * </ul>
 * Amounts are never added across tokens: callers group by token.
 */
public final class ExposurePaths {
    private ExposurePaths() {}

    /**
     * Flows between the subject and one listed address, for one label.
     *
     * @param flows   flows that create exposure, oldest first (empty when only ignored flows link them)
     * @param ignored inbound flows that do not create exposure (unverified token or dust), oldest first
     */
    public record Direct(AddressLabel label, List<ValueFlow> flows, List<ValueFlow> ignored) {
        public boolean exposed() { return !flows.isEmpty(); }
    }

    /**
     * A value path from a listed address to the subject.
     *
     * @param path flows from the one leaving the listed address ({@code path[0]}) to the one reaching the subject
     */
    public record Indirect(AddressLabel label, List<ValueFlow> path, double confidence) {
        public int hops() { return path.size(); }
        /** The smallest hop: the traced value is at most this amount, in this token. */
        public ValueFlow bottleneck() { return path.stream().min(Comparator.comparing(ValueFlow::amount)).orElseThrow(); }
    }

    /** True when this flow may carry exposure into {@code subject}'s view. */
    public static boolean counts(ValueFlow f, Address subject, DetectionConfig.Exposure cfg) {
        if (f.token().isUnverified()) return false;
        boolean inbound = f.to().equals(subject);
        return !inbound || f.amount().compareTo(cfg.dustThreshold(f.token().symbol())) >= 0;
    }

    /** Indirect tracing follows inbound value only: same rule as {@link #counts} for inbound flows. */
    public static boolean traceable(ValueFlow f, DetectionConfig.Exposure cfg) {
        return !f.token().isUnverified() && f.amount().compareTo(cfg.dustThreshold(f.token().symbol())) >= 0;
    }

    /** One entry per risky label of a direct counterparty, in the order the flows first met it. */
    public static List<Direct> direct(Address s, FlowStore store, LabelRepository labels, DetectionConfig.Exposure cfg) {
        Map<String, List<ValueFlow>> flowsByLabel = new LinkedHashMap<>();
        Map<String, List<ValueFlow>> dustByLabel = new LinkedHashMap<>();
        Map<String, AddressLabel> labelByKey = new LinkedHashMap<>();
        for (ValueFlow f : store.involving(s)) {
            if (f.from().equals(f.to())) continue;
            for (AddressLabel l : labels.findRisky(f.counterparty(s))) {
                labelByKey.putIfAbsent(l.key(), l);
                (counts(f, s, cfg) ? flowsByLabel : dustByLabel).computeIfAbsent(l.key(), k -> new ArrayList<>()).add(f);
            }
        }
        List<Direct> out = new ArrayList<>();
        labelByKey.forEach((key, l) -> out.add(new Direct(l, List.copyOf(flowsByLabel.getOrDefault(key, List.of())),
                List.copyOf(dustByLabel.getOrDefault(key, List.of())))));
        return out;
    }

    /** Value paths from listed addresses, 2..{@code cfg.maxHops()} hops, at most {@code cfg.maxPaths()}. */
    public static List<Indirect> indirect(Address s, FlowStore store, LabelRepository labels, DetectionConfig.Exposure cfg) {
        if (cfg.maxHops() < 2) return List.of();
        record Node(Address address, List<ValueFlow> path) {}
        Deque<Node> queue = new ArrayDeque<>();
        Set<Address> visited = new HashSet<>(Set.of(s));
        int budget = cfg.maxVisitedFlows();
        // Newest first: one flow is kept per sender, and the latest one admits every upstream flow that is not later
        // than it. Keeping the oldest missed value that reached the sender between two of its payments (review I7).
        // the subject's newest inbound flows, at most the budget (a hub holds tens of thousands); reading them does not
        // consume the walk's budget, as before: only the upstream exploration does
        for (ValueFlow f : store.recentIncoming(s, cfg.maxVisitedFlows())) {
            if (f.from().equals(s) || !traceable(f, cfg) || !visited.add(f.from())) continue;
            queue.add(new Node(f.from(), List.of(f)));
        }
        Map<String, Indirect> found = new LinkedHashMap<>();
        while (!queue.isEmpty() && budget > 0 && found.size() < cfg.maxPaths()) {
            Node n = queue.poll();
            List<AddressLabel> nodeLabels = labels.findByAddress(n.address());
            boolean risky = nodeLabels.stream().anyMatch(l -> l.category().risky());
            if (n.path().size() >= 2) {
                for (AddressLabel l : nodeLabels) {
                    if (!l.category().risky()) continue;
                    String key = l.address() + "|" + l.category() + "|" + l.source();
                    if (found.containsKey(key)) continue;
                    double conf = l.sourceReliability() * Math.pow(cfg.hopDecay(), n.path().size() - 1);
                    if (conf < cfg.minConfidence()) continue;
                    found.put(key, new Indirect(l, n.path(), conf));
                }
            }
            boolean stop = risky || nodeLabels.stream().anyMatch(l -> cfg.stopAt().contains(l.category()));
            if (stop || n.path().size() >= cfg.maxHops()) continue;
            ValueFlow downstream = n.path().getFirst();
            for (ValueFlow g : store.recentIncoming(n.address(), budget)) { // newest eligible flow per sender, as above
                if (--budget <= 0) break;
                if (g.timestamp().isAfter(downstream.timestamp()) || g.from().equals(n.address()) || !traceable(g, cfg)
                        || !visited.add(g.from())) continue;
                List<ValueFlow> p = new ArrayList<>(n.path().size() + 1);
                p.add(g); p.addAll(n.path());
                queue.add(new Node(g.from(), List.copyOf(p)));
            }
        }
        return List.copyOf(found.values());
    }

    /** The same settings with another hop limit (the endpoint's {@code depth}). */
    public static DetectionConfig.Exposure withMaxHops(DetectionConfig.Exposure c, int maxHops) {
        return new DetectionConfig.Exposure(maxHops, c.hopDecay(), c.minConfidence(), c.maxPaths(), c.maxVisitedFlows(),
                c.stopAt(), c.dustThresholds());
    }
}
