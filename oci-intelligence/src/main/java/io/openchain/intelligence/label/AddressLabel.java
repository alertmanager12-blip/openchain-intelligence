package io.openchain.intelligence.label;

import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.model.Address;
import java.time.Instant;
import java.util.Objects;

/**
 * What a given source asserts about an address.
 *
 * @param sourceReliability 0..1, how much the source is trusted (official list ~1.0, community report ~0.3-0.5)
 * @param evidenceLevel     CONFIRMED_BY_SOURCE only for authoritative sources (e.g. official sanctions list)
 * @param reference         optional pointer to the source entry (URL, list id)
 */
public record AddressLabel(Address address, LabelCategory category, String name, String source,
                           double sourceReliability, Instant observedAt, EvidenceLevel evidenceLevel, String reference) {
    public AddressLabel {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(evidenceLevel, "evidenceLevel");
        if (!(sourceReliability >= 0 && sourceReliability <= 1)) throw new IllegalArgumentException("sourceReliability must be in [0,1]");
        if (source.isBlank()) throw new IllegalArgumentException("source is required");
    }

    /** Identity of a label: one assertion per (address, category, source). */
    public String key() { return address + "|" + category + "|" + source; }
}
