package io.openchain.blockchain.backfill;

import io.openchain.core.model.Network;
import io.openchain.core.model.TokenFreeze;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Progress of a freeze backfill and the freezes it found (so that a restart can re-apply them without re-scanning:
 * chain-derived label state is in memory). Implementations: in memory, PostgreSQL ({@code backfill_progress},
 * {@code backfill_freeze_events}).
 */
public interface FreezeBackfillStore {

    /**
     * @param fromBlock        first block of the covered range
     * @param lastScannedBlock every block in {@code [fromBlock, lastScannedBlock]} has been scanned
     * @param freezesFound     freezes/unfreezes stored so far
     */
    record Progress(String id, Network network, long fromBlock, long lastScannedBlock, long freezesFound, Instant updatedAt) {}

    Optional<Progress> load(String id);

    /**
     * Atomically stores the freezes of one scanned chunk (an event id already stored is ignored) and the new progress.
     */
    void saveChunk(Progress progress, List<TokenFreeze> freezes);

    /** Stored freezes of a backfill, ordered by (block, log index). */
    List<TokenFreeze> freezes(String id);
}
