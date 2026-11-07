package io.openchain.intelligence.detection.detectors;

import static io.openchain.core.detection.SignalText.*;
import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal;
import io.openchain.core.detection.Signal.Evidence;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.detection.SignalType;
import io.openchain.intelligence.detection.DetectionContext;
import io.openchain.intelligence.detection.SignalIds;
import io.openchain.intelligence.flow.ValueFlow;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Most of a pre-existing balance leaves within a short window (possible compromise/exit).
 * Balance: on real chains ({@link io.openchain.intelligence.detection.BalanceBook#authoritative}) the provider-reported
 * balance before the first outflow of the window (no signal when it is unknown); elsewhere the estimate from observed
 * flows of that token (simulated chains, tests).
 */
public final class RapidDrainDetector extends AbstractDetector {
    public RapidDrainDetector() { super("rapid-drain", 1); }

    @Override public List<Signal> detect(DetectionContext ctx) {
        if (!ctx.triggerIsOutgoing()) return List.of();
        ValueFlow t = ctx.trigger();
        var cfg = ctx.config().rapidDrain();
        Instant start = t.timestamp().minus(cfg.window());
        BigDecimal before = BigDecimal.ZERO, inWin = BigDecimal.ZERO, outWin = BigDecimal.ZERO;
        List<ValueFlow> outs = new ArrayList<>();
        for (ValueFlow f : sameToken(ctx.flows().involving(ctx.subject()), t)) {
            if (f.timestamp().isAfter(t.timestamp())) continue;
            boolean in = f.to().equals(ctx.subject()), out = f.from().equals(ctx.subject());
            if (in && out) continue;
            if (f.timestamp().isBefore(start)) before = in ? before.add(f.amount()) : before.subtract(f.amount());
            else if (in) inWin = inWin.add(f.amount());
            else { outWin = outWin.add(f.amount()); outs.add(f); }
        }
        String basis = "estimated";
        if (ctx.balances() != null && ctx.balances().authoritative(t.network())) {
            // real chain: the balance comes from the provider (end of the block before the first outflow of the window);
            // no real balance, no signal (a flow-based estimate ignores everything received before the indexer start)
            if (outs.isEmpty()) return List.of();
            ValueFlow first = outs.stream().min(java.util.Comparator.comparing(ValueFlow::timestamp)).orElseThrow();
            var real = ctx.balances().senderBalanceBefore(first.eventId());
            if (real.isEmpty()) return List.of();
            before = real.get();
            inWin = BigDecimal.ZERO;
            for (ValueFlow f : sameToken(ctx.flows().incoming(ctx.subject()), t)) {
                if (!f.from().equals(ctx.subject()) && !f.timestamp().isBefore(first.timestamp()) && !f.timestamp().isAfter(t.timestamp())) {
                    inWin = inWin.add(f.amount());
                }
            }
            basis = "provider-reported";
        }
        if (before.signum() <= 0 || outWin.compareTo(cfg.minAmount()) < 0) return List.of();
        BigDecimal available = before.add(inWin);
        double ratio = outWin.divide(available, MathContext.DECIMAL64).doubleValue();
        if (ratio < cfg.minRatio()) return List.of();
        List<Evidence> ev = new ArrayList<>(flowEvidence(outs, "outflow in window", ctx.config().maxEvidenceItems()));
        ev.add(new Evidence("pattern", String.format(java.util.Locale.ROOT,
                "%.0f%% of %s available balance %s %s (pre-window %s + inflow %s) sent within %s",
                ratio * 100, basis, plain(available), t.token().symbol(), plain(before), plain(inWin), human(cfg.window())),
                t.txHash(), qualifiedId(), t.timestamp()));
        long recipients = outs.stream().map(ValueFlow::to).distinct().count();
        String summary = "Sent " + percent(ratio) + " of its " + t.token().symbol() + " balance (" + amount(outWin, t.token().symbol())
                + ") to " + plural(recipients, "address", "addresses") + " within " + duration(cfg.window()) + ".";
        return List.of(signal(ctx, SignalType.RAPID_DRAIN, ctx.subject(), t.txHash(), Severity.MEDIUM, 0.7,
                EvidenceLevel.RISK_SIGNAL, ev, t.tokenKey() + "|" + SignalIds.window(t.timestamp(), cfg.window()), summary));
    }
}
