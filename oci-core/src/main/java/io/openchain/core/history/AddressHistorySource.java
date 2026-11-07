package io.openchain.core.history;

import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import java.time.Instant;
import java.util.List;

/**
 * Per-address history source (ADR-0020): lists past events of ONE address that the live indexer has not seen, so that
 * an arbitrary address can be analysed on demand. Implementations are the platform's own adapters (ADR-0003):
 * {@code eth_getLogs} by topic (EVM, ERC-20 transfers and approvals), TronGrid account endpoints (TRON). A source walks
 * the requested block range BACKWARDS (newest first), one bounded page per call, so that a capped backfill keeps the
 * most recent history.
 * <p>Events carry the same deterministic ids as the live indexer whenever the source exposes the same identity
 * ({@code network:txHash:logIndex}, {@code network:txHash:native}), so that replaying an event already indexed is a no-op.
 * Only finalized data is returned (never reorgable blocks).
 */
public interface AddressHistorySource {

    /** Source name shown to analysts, e.g. {@code eth_getLogs}, {@code trongrid}. */
    String sourceId();

    Network network();

    /** What this source can see. */
    Coverage coverage();

    /** Highest block a history backfill may query: finalized (or head - confirmations). */
    long historySafeHead();

    /**
     * One page of the history of {@code address} in {@code [fromBlock, toBlock]}, newest first.
     *
     * @param cursor null for the first page, else the {@link HistoryPage#nextCursor()} of the previous page
     * @throws io.openchain.core.provider.ProviderException on provider errors (the caller keeps its progress and may resume)
     */
    HistoryPage fetch(Address address, long fromBlock, long toBlock, String cursor);

    /**
     * @param tokenTransfers    ERC-20 / TRC-20 transfers where the address is sender or recipient
     * @param approvals         allowances granted by the address (owner)
     * @param nativeTransfers   native coin transfers (ETH, TRX...)
     * @param internalTransfers value moved by contract calls (traces)
     */
    record Coverage(boolean tokenTransfers, boolean approvals, boolean nativeTransfers, boolean internalTransfers) {}

    /**
     * @param events           events found by this page (any order, deterministic ids)
     * @param coveredFromBlock after this page, every event of the address in {@code [coveredFromBlock, toBlock]} has been
     *                         returned; {@code toBlock + 1} when the source cannot tell yet (e.g. timestamp-paged APIs)
     * @param coveredFromTime  same guarantee expressed in time when known (null otherwise)
     * @param nextCursor       null when the whole range has been listed
     * @param queries          provider calls made for this page (observability)
     */
    record HistoryPage(List<ChainEvent> events, long coveredFromBlock, Instant coveredFromTime, String nextCursor, int queries) {
        public HistoryPage { events = List.copyOf(events); }

        public boolean last() { return nextCursor == null; }
    }
}
