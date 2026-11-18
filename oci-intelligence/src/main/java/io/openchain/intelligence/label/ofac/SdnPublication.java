package io.openchain.intelligence.label.ofac;

import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.LabelCategory;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/** A publication of the OFAC SDN list reduced to the sanctioned addresses of the indexed networks (ADR-0027). */
public record SdnPublication(LocalDate issued, List<SdnAddress> addresses) {
    public static final String SOURCE = "OFAC SDN";

    public SdnPublication {
        addresses = List.copyOf(addresses);
    }

    /** Confirmed sanctions labels (only the listing is confirmed: ADR-0023 rule 4), dated by the publication. */
    public List<AddressLabel> toLabels() {
        var at = issued.atStartOfDay().toInstant(ZoneOffset.UTC);
        return addresses.stream().map(a -> new AddressLabel(a.address(), LabelCategory.SANCTIONED, a.name(), SOURCE, 1.0, at,
                EvidenceLevel.CONFIRMED_BY_SOURCE, "OFAC SDN uid " + a.sdnUid() + " (" + String.join(", ", a.programs())
                        + "; listed under " + String.join(", ", a.currencies()) + "), publication of " + issued)).toList();
    }
}
