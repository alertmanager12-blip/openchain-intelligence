package io.openchain.blockchain.indexer;

import io.openchain.core.event.ChainEvent;
import java.util.List;

/**
 * Downstream of the indexer (detection pipeline, queue, ...). Delivery is at-least-once: an event may be
 * re-emitted after a crash between emit and checkpoint save, so consumers must be idempotent on
 * ({@code eventId}, {@code removed}, {@code blockHash}). A retraction ({@code removed=true}) may be followed by
 * a re-emission with the SAME eventId when the transaction is re-included by the new fork.
 */
@FunctionalInterface
public interface ChainEventSink {
    void emit(ChainEvent event);

    /**
     * Called once per block with all its new events, in order, BEFORE they are emitted: lets a consumer prefetch
     * what it needs for the whole block concurrently (e.g. balances). Must not emit or alter anything.
     */
    default void prepare(List<ChainEvent> blockEvents) {}
}
