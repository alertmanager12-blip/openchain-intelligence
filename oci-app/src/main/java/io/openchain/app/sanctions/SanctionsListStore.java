package io.openchain.app.sanctions;

import io.openchain.intelligence.label.ofac.SdnPublication;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/** Publications of a sanctions list: the accepted ones (with their addresses) and the refused ones (ADR-0027). */
public interface SanctionsListStore {
    /** An accepted publication as stored. */
    record Stored(long id, Instant fetchedAt, String sha256, SdnPublication publication) {}

    Optional<Stored> latestAccepted();

    Stored saveAccepted(SdnPublication publication, Instant fetchedAt, String sha256);

    /** A refused download: kept for the record, never applied. {@code issued} is null when the document did not parse. */
    void saveRefused(LocalDate issued, Instant at, String sha256, String reason);
}
