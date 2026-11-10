package io.openchain.intelligence.detection.detectors;

import static io.openchain.core.detection.SignalText.*;
import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal;
import io.openchain.core.detection.Signal.Evidence;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.detection.SignalType;
import io.openchain.core.model.Address;
import io.openchain.intelligence.detection.DetectionConfig;
import io.openchain.intelligence.detection.DetectionContext;
import io.openchain.intelligence.flow.FlowStore;
import io.openchain.intelligence.flow.ValueFlow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Value leaving the wallet and coming back through 1..maxDepth-1 intermediaries (A -> B -> ... -> A), with
 * non-decreasing timestamps, within the window. When a trigger is present only cycles containing it are reported.
 * Bounded DFS (depth + expansion budget). One signal per <b>return flow</b> (the transfer that closes the cycle), listing
 * every path found for it: before, ten paths closed by one return gave up to five separate signals for one behaviour.
 */
public final class CircularFlowDetector extends AbstractDetector {
    private static final int MAX_CYCLES = 5;

    public CircularFlowDetector() { super("circular-flow", 1); }

    @Override public List<Signal> detect(DetectionContext ctx) {
        var cfg = ctx.config().circularFlow();
        Address a = ctx.subject();
        Instant ref = ctx.referenceTime();
        List<List<ValueFlow>> cycles = new ArrayList<>();
        int[] budget = {cfg.maxExpansions()};
        for (ValueFlow start : between(ctx.flows().outgoing(a), ref.minus(cfg.window()), ref)) {
            if (start.to().equals(a)) continue;
            Set<Address> visited = new HashSet<>(Set.of(a, start.to()));
            List<ValueFlow> path = new ArrayList<>(List.of(start));
            dfs(ctx.flows(), cfg, a, start, path, visited, cycles, budget);
            if (cycles.size() >= MAX_CYCLES * 4 || budget[0] <= 0) break;
        }
        // group the cycles by the flow that closes them (the return), keeping discovery order
        java.util.Map<String, List<List<ValueFlow>>> byReturn = new java.util.LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (List<ValueFlow> c : cycles) {
            if (ctx.trigger() != null && c.stream().noneMatch(f -> f.eventId().equals(ctx.trigger().eventId()))) continue;
            if (!seen.add(c.stream().map(ValueFlow::eventId).sorted().collect(Collectors.joining(",")))) continue;
            byReturn.computeIfAbsent(c.getLast().eventId(), k -> new ArrayList<>()).add(c);
        }
        List<Signal> out = new ArrayList<>();
        for (var group : byReturn.entrySet()) {
            if (out.size() >= MAX_CYCLES) break;
            List<List<ValueFlow>> paths = group.getValue();
            ValueFlow back = paths.getFirst().getLast();
            List<ValueFlow> flows = new ArrayList<>();
            for (List<ValueFlow> c : paths) for (ValueFlow f : c) if (!flows.contains(f)) flows.add(f);
            List<Evidence> ev = new ArrayList<>(flowEvidence(flows, "cycle hop", ctx.config().maxEvidenceItems()));
            for (List<ValueFlow> c : paths.subList(0, Math.min(MAX_CYCLES, paths.size()))) {
                String route = a.value() + c.stream().map(f -> " -> " + f.to().value()).collect(Collectors.joining());
                ev.add(new Evidence("path", c.size() + "-hop cycle: " + route, back.txHash(), qualifiedId(), back.timestamp()));
            }
            if (paths.size() > MAX_CYCLES)
                ev.add(new Evidence("path", (paths.size() - MAX_CYCLES) + " more path(s) closed by the same return", back.txHash(), qualifiedId(), back.timestamp()));
            boolean deep = paths.stream().anyMatch(c -> c.size() >= 3);
            Severity sev = deep ? Severity.MEDIUM : Severity.LOW;
            List<ValueFlow> starts = paths.stream().map(List::getFirst).distinct().toList();
            java.math.BigDecimal sent = starts.stream().map(ValueFlow::amount).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
            ValueFlow first = starts.stream().min(java.util.Comparator.comparing(ValueFlow::timestamp)).orElseThrow();
            int intermediaries = (int) paths.stream().flatMap(c -> c.subList(0, c.size() - 1).stream()).map(ValueFlow::to).distinct().count();
            String summary = "Sent " + amount(sent, first.token().symbol()) + " that came back to it ("
                    + amount(back.amount(), back.token().symbol()) + ") through " + plural(intermediaries, "intermediary address", "intermediary addresses")
                    + (paths.size() > 1 ? " on " + paths.size() + " paths" : "")
                    + " within " + duration(java.time.Duration.between(first.timestamp(), back.timestamp())) + ".";
            out.add(signal(ctx, SignalType.CIRCULAR_FLOW, a, back.txHash(), sev, 0.6, EvidenceLevel.RISK_SIGNAL, ev, "return|" + group.getKey(), summary));
        }
        return out;
    }

    private void dfs(FlowStore flows, DetectionConfig.CircularFlow cfg, Address origin, ValueFlow start, List<ValueFlow> path,
                     Set<Address> visited, List<List<ValueFlow>> cycles, int[] budget) {
        ValueFlow last = path.getLast();
        Instant deadline = start.timestamp().plus(cfg.window());
        for (ValueFlow g : flows.outgoing(last.to())) {
            if (--budget[0] <= 0) return;
            if (g.timestamp().isBefore(last.timestamp()) || g.timestamp().isAfter(deadline)) continue;
            if (cfg.sameTokenOnly() && !g.tokenKey().equals(start.tokenKey())) continue;
            if (g.to().equals(origin)) {
                List<ValueFlow> c = new ArrayList<>(path); c.add(g); cycles.add(c);
            } else if (!visited.contains(g.to()) && path.size() + 1 < cfg.maxDepth()) {
                visited.add(g.to()); path.add(g);
                dfs(flows, cfg, origin, start, path, visited, cycles, budget);
                path.removeLast(); visited.remove(g.to());
            }
        }
    }
}
