package io.openchain.intelligence.detection.detectors;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Contract owner/admin changed (CONTRACT_ADMIN_CHANGE). Renouncement (new owner/admin = zero address, EVM
 * {@code 0x000...0} or TRON {@code T9yD14Nj9j7xAB4dbGeiX9h8unkKHxuWwb}, see {@link Address#isZero()}) is INFO; listed
 * new admin is HIGH. An implementation upgrade to the zero address is not a renouncement.
 */
public final class OwnershipChangeDetector extends AbstractDetector {
    public OwnershipChangeDetector() { super("ownership-change", 1); }

    @Override public List<Signal> detect(DetectionContext ctx) {
        ChainEvent e = ctx.event();
        if (e == null || e.type() != ChainEvent.EventType.CONTRACT_ADMIN_CHANGE || e.removed()) return List.of();
        Optional<Address> contract = EventFields.address(e, EventFields.ADMIN_CONTRACT);
        if (contract.isEmpty()) return List.of();
        Optional<Address> prev = EventFields.address(e, EventFields.ADMIN_PREVIOUS);
        Optional<Address> neu = EventFields.address(e, EventFields.ADMIN_NEW);
        String role = EventFields.string(e, EventFields.ADMIN_ROLE).orElse("owner");
        String tx = EventFields.string(e, EventFields.TX_HASH).orElse(null);
        boolean renounced = !io.openchain.core.model.ContractAdminChange.ROLE_IMPLEMENTATION.equals(role) && neu.map(Address::isZero).orElse(false);
        List<AddressLabel> newLabels = neu.map(a -> ctx.labels().findRisky(a)).orElse(List.of());
        List<Evidence> ev = new ArrayList<>();
        ev.add(new Evidence("event", role + " of contract " + contract.get().value() + " changed from "
                + prev.map(Address::value).orElse("?") + " to " + neu.map(Address::value).orElse("?") + (renounced ? " (renounced)" : ""),
                tx, "chain:" + e.network().id(), e.eventTime()));
        for (AddressLabel l : newLabels)
            ev.add(new Evidence("label", "new " + role + " listed as " + l.category() + " by " + l.source(), l.reference(), l.source(), l.observedAt()));
        Severity sev = renounced ? Severity.INFO : newLabels.isEmpty() ? Severity.MEDIUM : Severity.HIGH;
        double conf = newLabels.isEmpty() ? 0.9 : newLabels.stream().mapToDouble(AddressLabel::sourceReliability).max().orElse(0.9);
        return List.of(signal(ctx, SignalType.OWNERSHIP_CHANGE, contract.get(), tx, sev, conf, EvidenceLevel.RISK_SIGNAL, ev, e.eventId()));
    }
}
