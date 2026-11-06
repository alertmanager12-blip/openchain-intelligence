package io.openchain.intelligence.detection;

import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.Address;
import io.openchain.intelligence.flow.FlowStore;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.label.LabelRepository;
import io.openchain.intelligence.profile.WalletProfile;
import java.time.Instant;
import java.util.Objects;

/**
 * Input of one detection run for one subject address.
 *
 * @param trigger the new value flow that caused the run (nullable)
 * @param event   the non-flow event that caused the run, e.g. APPROVAL (nullable)
 * @param profile current profile of the subject (nullable)
 * @param now     wall clock, used only as {@code detectedAt}; windows use on-chain time ({@link #referenceTime()})
 */
public record DetectionContext(Address subject, ValueFlow trigger, ChainEvent event, WalletProfile profile,
                               FlowStore flows, LabelRepository labels, DetectionConfig config, Instant now) {
    public DetectionContext {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(flows, "flows");
        Objects.requireNonNull(labels, "labels");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(now, "now");
    }

    /** On-chain time of the trigger/event, falling back to {@code now}. */
    public Instant referenceTime() {
        if (trigger != null) return trigger.timestamp();
        if (event != null && event.eventTime() != null) return event.eventTime();
        return now;
    }

    /** True when the trigger is an outgoing flow of the subject. */
    public boolean triggerIsOutgoing() { return trigger != null && trigger.from().equals(subject) && !trigger.to().equals(subject); }
}
