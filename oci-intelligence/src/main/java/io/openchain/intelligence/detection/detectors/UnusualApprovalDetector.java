package io.openchain.intelligence.detection.detectors;

import static io.openchain.core.detection.SignalText.*;
import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal;
import io.openchain.core.detection.Signal.Evidence;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.detection.SignalType;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.Address;
import io.openchain.intelligence.detection.DetectionContext;
import io.openchain.intelligence.detection.EventFields;
import io.openchain.intelligence.label.AddressLabel;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Unlimited (or near-unlimited) token allowance granted by the owner; escalated when the spender is listed. */
public final class UnusualApprovalDetector extends AbstractDetector {
    public UnusualApprovalDetector() { super("unusual-approval", 1); }

    @Override public List<Signal> detect(DetectionContext ctx) {
        ChainEvent e = ctx.event();
        if (e == null || e.type() != ChainEvent.EventType.APPROVAL || e.removed()) return List.of();
        Optional<Address> owner = EventFields.address(e, EventFields.APPROVAL_OWNER);
        Optional<Address> spender = EventFields.address(e, EventFields.APPROVAL_SPENDER);
        Optional<BigInteger> amount = EventFields.bigInteger(e, EventFields.APPROVAL_AMOUNT_RAW);
        if (owner.isEmpty() || spender.isEmpty() || amount.isEmpty()) return List.of();
        if (amount.get().compareTo(ctx.config().approval().unlimitedThreshold()) < 0) return List.of();
        String tx = EventFields.string(e, EventFields.TX_HASH).orElse(null);
        String token = EventFields.string(e, EventFields.TOKEN_SYMBOL).or(() -> EventFields.string(e, EventFields.TOKEN_CONTRACT)).orElse("?");
        List<AddressLabel> spenderLabels = ctx.labels().findRisky(spender.get());
        List<Evidence> ev = new ArrayList<>();
        ev.add(new Evidence("event", "unlimited allowance on " + token + " granted by " + owner.get().value() + " to spender "
                + spender.get().value() + " (raw amount " + amount.get() + " >= " + ctx.config().approval().unlimitedThreshold() + ")",
                tx, "chain:" + e.network().id(), e.eventTime()));
        for (AddressLabel l : spenderLabels)
            ev.add(new Evidence("label", "spender listed as " + l.category() + " by " + l.source(), l.reference(), l.source(), l.observedAt()));
        Severity sev = spenderLabels.isEmpty() ? Severity.LOW : Severity.HIGH;
        double conf = spenderLabels.isEmpty() ? 0.5 : spenderLabels.stream().mapToDouble(AddressLabel::sourceReliability).max().orElse(0.5);
        String summary = "Granted an unlimited " + token + " allowance to " + shortAddress(spender.get().value())
                + (spenderLabels.isEmpty() ? ", which can move all of its " + token + " at any time."
                : ", an address listed as " + category(spenderLabels.getFirst().category().name()) + " by " + spenderLabels.getFirst().source() + ".");
        return List.of(signal(ctx, SignalType.UNUSUAL_APPROVAL, owner.get(), tx, sev, conf, EvidenceLevel.RISK_SIGNAL, ev, e.eventId(), summary));
    }
}
