package io.openchain.intelligence.detection.detectors;

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
 * Bounded DFS (depth + expansion budget).
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
        List<Signal> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (List<ValueFlow> c : cycles) {
            if (ctx.trigger() != null && c.stream().noneMatch(f -> f.eventId().equals(ctx.trigger().eventId()))) continue;
            String key = c.stream().map(ValueFlow::eventId).sorted().collect(Collectors.joining(","));
            if (!seen.add(key) || out.size() >= MAX_CYCLES) continue;
            String route = a.value() + c.stream().map(f -> " -> " + f.to().value()).collect(Collectors.joining());
            List<Evidence> ev = new ArrayList<>(flowEvidence(c, "cycle hop", ctx.config().maxEvidenceItems()));
            ev.add(new Evidence("path", c.size() + "-hop cycle: " + route, c.getLast().txHash(), qualifiedId(), c.getLast().timestamp()));
            Severity sev = c.size() >= 3 ? Severity.MEDIUM : Severity.LOW;
            out.add(signal(ctx, SignalType.CIRCULAR_FLOW, a, c.getLast().txHash(), sev, 0.6, EvidenceLevel.RISK_SIGNAL, ev, key));
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
