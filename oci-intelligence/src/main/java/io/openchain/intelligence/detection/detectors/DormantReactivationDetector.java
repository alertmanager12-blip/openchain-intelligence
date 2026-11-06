package io.openchain.intelligence.detection.detectors;

import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal;
import io.openchain.core.detection.Signal.Evidence;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.detection.SignalType;
import io.openchain.intelligence.detection.DetectionContext;
import io.openchain.intelligence.flow.ValueFlow;
import java.time.Duration;
import java.util.List;

/** Wallet sends value after a long period without any observed activity. Behavioural ANOMALY only. */
public final class DormantReactivationDetector extends AbstractDetector {
    public DormantReactivationDetector() { super("dormant-reactivation", 1); }

    @Override public List<Signal> detect(DetectionContext ctx) {
        if (!ctx.triggerIsOutgoing()) return List.of();
        ValueFlow t = ctx.trigger();
        ValueFlow prev = null;
        for (ValueFlow f : ctx.flows().involving(ctx.subject()))
            if (f.timestamp().isBefore(t.timestamp())) prev = f; // chronological: keeps the latest prior
        if (prev == null) return List.of();
        Duration gap = Duration.between(prev.timestamp(), t.timestamp());
        if (gap.compareTo(ctx.config().dormancy().minDormancy()) < 0) return List.of();
        var ev = List.of(flowEvidence(prev, "last activity before dormancy"), flowEvidence(t, "reactivation"),
                new Evidence("pattern", "no activity for " + human(gap) + " (threshold " + human(ctx.config().dormancy().minDormancy()) + ")",
                        t.txHash(), qualifiedId(), t.timestamp()));
        return List.of(signal(ctx, SignalType.DORMANT_REACTIVATION, ctx.subject(), t.txHash(), Severity.LOW, 0.8,
                EvidenceLevel.ANOMALY, ev, t.eventId()));
    }
}
