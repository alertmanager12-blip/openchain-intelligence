package io.openchain.app.history;

import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.Network;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Progress of the on-demand address history backfills (ADR-0020) and the events they found. Chain-derived state is in
 * memory: the stored events are re-applied at startup (no RPC), and an interrupted backfill resumes from its cursor.
 * Implementations: in memory, PostgreSQL ({@code address_history_progress}, {@code address_history_events}).
 */
public interface AddressHistoryStore {

    enum Status { PENDING, RUNNING, COMPLETE, TRUNCATED, FAILED;
        public boolean terminal() { return this == COMPLETE || this == TRUNCATED; }
    }

    /**
     * @param id               {@code history:<network>:<address>}
     * @param fromBlock        lowest block of the target range (head of the range minus the configured lookback)
     * @param toBlock          highest block of the target range (the live indexer covers the blocks after it)
     * @param coveredFromBlock every event of the address in {@code [coveredFromBlock, toBlock]} has been stored (null: none yet)
     * @param cursor           source cursor to resume from (null: start, or finished)
     */
    record Progress(String id, Network network, String address, String source, Status status, long fromBlock, long toBlock,
                    Long coveredFromBlock, Instant coveredFromTime, String cursor, long eventsFound, int pages, boolean truncated,
                    String lastError, Instant createdAt, Instant updatedAt) {

        public Progress with(Status s, String error, Instant now) {
            return new Progress(id, network, address, source, s, fromBlock, toBlock, coveredFromBlock, coveredFromTime, cursor,
                    eventsFound, pages, truncated || s == Status.TRUNCATED, error, createdAt, now);
        }
    }

    Optional<Progress> load(String id);

    List<Progress> all();

    /** Inserts or replaces the progress row (status changes). */
    void save(Progress progress);

    /** Atomically stores the events of one page (an event id already stored for this backfill is ignored) and the progress. */
    void saveChunk(Progress progress, List<ChainEvent> events);

    /** Stored events of a backfill, oldest first (event time, then id). */
    List<ChainEvent> events(String id);

    static String idOf(Network n, String address) { return "history:" + n.id() + ":" + address; }
}
