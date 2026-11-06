package io.openchain.intelligence.detection.detectors;

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
 * Balance is the estimate from observed flows of that token.
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
        if (before.signum() <= 0 || outWin.compareTo(cfg.minAmount()) < 0) return List.of();
        BigDecimal available = before.add(inWin);
        double ratio = outWin.divide(available, MathContext.DECIMAL64).doubleValue();
        if (ratio < cfg.minRatio()) return List.of();
        List<Evidence> ev = new ArrayList<>(flowEvidence(outs, "outflow in window", ctx.config().maxEvidenceItems()));
        ev.add(new Evidence("pattern", String.format(java.util.Locale.ROOT,
                "%.0f%% of estimated available balance %s %s (pre-window %s + inflow %s) sent within %s",
                ratio * 100, plain(available), t.token().symbol(), plain(before), plain(inWin), human(cfg.window())),
                t.txHash(), qualifiedId(), t.timestamp()));
        return List.of(signal(ctx, SignalType.RAPID_DRAIN, ctx.subject(), t.txHash(), Severity.MEDIUM, 0.7,
                EvidenceLevel.RISK_SIGNAL, ev, t.tokenKey() + "|" + SignalIds.window(t.timestamp(), cfg.window())));
    }
}
