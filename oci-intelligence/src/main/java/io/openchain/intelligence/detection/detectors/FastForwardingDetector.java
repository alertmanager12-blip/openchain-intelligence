package io.openchain.intelligence.detection.detectors;

import static io.openchain.core.detection.SignalText.*;
import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal;
import io.openchain.core.detection.Signal.Evidence;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.detection.SignalType;
import io.openchain.intelligence.detection.DetectionContext;
import io.openchain.intelligence.flow.ValueFlow;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Funds forwarded almost entirely shortly after receipt (pass-through behaviour). One signal per receipt episode. */
public final class FastForwardingDetector extends AbstractDetector {
    public FastForwardingDetector() { super("fast-forwarding", 1); }

    @Override public List<Signal> detect(DetectionContext ctx) {
        if (!ctx.triggerIsOutgoing()) return List.of();
        ValueFlow t = ctx.trigger();
        var cfg = ctx.config().fastForwarding();
        List<ValueFlow> ins = between(sameToken(ctx.flows().incoming(ctx.subject()), t), t.timestamp().minus(cfg.window()), t.timestamp())
                .stream().filter(f -> !f.from().equals(ctx.subject())).toList();
        if (ins.isEmpty()) return List.of();
        BigDecimal in = sum(ins);
        if (in.signum() <= 0 || in.compareTo(cfg.minInflow()) < 0) return List.of();
        ValueFlow first = ins.getFirst();
        List<ValueFlow> outs = between(sameToken(ctx.flows().outgoing(ctx.subject()), t), first.timestamp(), t.timestamp())
                .stream().filter(f -> !f.to().equals(ctx.subject())).toList();
        BigDecimal out = sum(outs);
        double ratio = out.divide(in, MathContext.DECIMAL64).doubleValue();
        if (ratio < cfg.minRatio()) return List.of();
        Duration delay = Duration.between(first.timestamp(), t.timestamp());
        double speed = 1 - Math.min(1, delay.toSeconds() / (double) Math.max(1, cfg.window().toSeconds()));
        List<Evidence> ev = new ArrayList<>(flowEvidence(ins, "received", ctx.config().maxEvidenceItems() / 2));
        ev.addAll(flowEvidence(outs, "forwarded", ctx.config().maxEvidenceItems() / 2));
        ev.add(new Evidence("pattern", String.format(java.util.Locale.ROOT, "%.0f%% of %s %s received forwarded within %s",
                ratio * 100, plain(in), t.token().symbol(), human(delay)), t.txHash(), qualifiedId(), t.timestamp()));
        long recipients = outs.stream().map(ValueFlow::to).distinct().count();
        String summary = "Forwarded " + percent(ratio) + " of the " + amount(in, t.token().symbol()) + " it received to "
                + plural(recipients, "address", "addresses") + " within " + duration(delay) + ".";
        return List.of(signal(ctx, SignalType.FAST_FORWARDING, ctx.subject(), t.txHash(), Severity.MEDIUM, 0.6 + 0.3 * speed,
                EvidenceLevel.RISK_SIGNAL, ev, t.tokenKey() + "|" + first.eventId(), summary));
    }
}
