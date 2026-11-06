package io.openchain.intelligence.detection.detectors;

import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal;
import io.openchain.core.detection.Signal.Evidence;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.detection.SignalType;
import io.openchain.core.model.Address;
import io.openchain.intelligence.detection.DetectionContext;
import io.openchain.intelligence.detection.SignalIds;
import io.openchain.intelligence.flow.ValueFlow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fan-out: value sent to many distinct recipients within a window (distribution / layering pattern). */
public final class FanOutDetector extends AbstractDetector {
    public FanOutDetector() { super("fan-out", 1); }

    @Override public List<Signal> detect(DetectionContext ctx) {
        if (!ctx.triggerIsOutgoing()) return List.of();
        ValueFlow t = ctx.trigger();
        var cfg = ctx.config().fanOut();
        Map<Address, ValueFlow> firstPerRecipient = new LinkedHashMap<>();
        for (ValueFlow f : between(ctx.flows().outgoing(ctx.subject()), t.timestamp().minus(cfg.window()), t.timestamp()))
            if (!f.to().equals(ctx.subject())) firstPerRecipient.putIfAbsent(f.to(), f);
        int n = firstPerRecipient.size();
        if (n < cfg.minDistinctRecipients()) return List.of();
        List<Evidence> ev = new ArrayList<>(flowEvidence(new ArrayList<>(firstPerRecipient.values()), "distinct recipient", ctx.config().maxEvidenceItems()));
        ev.add(new Evidence("pattern", n + " distinct recipients within " + human(cfg.window()) + " (threshold " + cfg.minDistinctRecipients() + ")",
                t.txHash(), qualifiedId(), t.timestamp()));
        Severity sev = n >= 2 * cfg.minDistinctRecipients() ? Severity.MEDIUM : Severity.LOW;
        return List.of(signal(ctx, SignalType.FLOW_CONCENTRATION, ctx.subject(), t.txHash(), sev, 0.6, EvidenceLevel.RISK_SIGNAL, ev,
                "fan-out|" + SignalIds.window(t.timestamp(), cfg.window())));
    }
}
