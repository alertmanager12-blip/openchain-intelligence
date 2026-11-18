package io.openchain.app.flowjournal;

import io.openchain.core.model.Address;
import io.openchain.intelligence.flow.ValueFlow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The persisted transfers of watched addresses (ADR-0026 amendment 1). */
public interface FlowJournal {
    enum Source { LIVE, HISTORY }

    /** Writes transfers once (same event: left as it is); returns the number of new rows. */
    int append(List<ValueFlow> flows, Source source);

    /** Transfers involving the address in [from, to], oldest first, at most {@code limit}. */
    List<ValueFlow> involving(Address a, Instant from, Instant to, int limit);

    /** Since when the journal is complete for this address, if it is watched. */
    Optional<Instant> coveredSince(Address a);

    /** Records the coverage start of a watched address; an earlier start already recorded is kept. */
    void cover(Address a, Instant since, Instant enrolledAt);

    /** Creates the partition of the month holding {@code at}, if missing. */
    default void ensurePartition(Instant at) { }
}
