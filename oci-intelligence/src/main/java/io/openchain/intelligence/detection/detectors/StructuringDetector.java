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
import java.util.ArrayList;
import java.util.List;

/** Several outgoing transfers just below a reporting threshold within a window (possible threshold avoidance). */
public final class StructuringDetector extends AbstractDetector {
    public StructuringDetector() { super("structuring", 1); }

    @Override public List<Signal> detect(DetectionContext ctx) {
        if (!ctx.triggerIsOutgoing()) return List.of();
        ValueFlow t = ctx.trigger();
        var cfg = ctx.config().structuring();
        BigDecimal threshold = cfg.thresholdFor(t.token().symbol());
        BigDecimal lower = threshold.multiply(BigDecimal.valueOf(1 - cfg.marginRatio()));
        List<ValueFlow> hits = between(sameToken(ctx.flows().outgoing(ctx.subject()), t), t.timestamp().minus(cfg.window()), t.timestamp())
                .stream().filter(f -> f.amount().compareTo(lower) >= 0 && f.amount().compareTo(threshold) < 0).toList();
        if (hits.size() < cfg.minCount()) return List.of();
        BigDecimal total = sum(hits);
        List<Evidence> ev = new ArrayList<>(flowEvidence(hits, "just below threshold", ctx.config().maxEvidenceItems()));
        ev.add(new Evidence("pattern", hits.size() + " transfers in [" + plain(lower) + ", " + plain(threshold) + ") "
                + t.token().symbol() + " within " + human(cfg.window()) + ", total " + plain(total), t.txHash(), qualifiedId(), t.timestamp()));
        double conf = Math.min(0.85, 0.5 + 0.1 * (hits.size() - cfg.minCount()));
        String sym = t.token().symbol();
        String summary = "Sent " + plural(hits.size(), "transfer", "transfers") + " just below the " + amount(threshold, sym)
                + " threshold within " + duration(cfg.window()) + " (total " + amount(total, sym) + ").";
        return List.of(signal(ctx, SignalType.STRUCTURING, ctx.subject(), t.txHash(), Severity.MEDIUM, conf, EvidenceLevel.RISK_SIGNAL, ev,
                t.tokenKey() + "|" + SignalIds.window(t.timestamp(), cfg.window()), summary));
    }
}
