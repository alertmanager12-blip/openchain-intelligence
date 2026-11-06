package io.openchain.blockchain.indexer;

import io.openchain.core.event.ChainEvent;

/**
 * Downstream of the indexer (detection pipeline, queue, ...). Delivery is at-least-once: an event may be
 * re-emitted after a crash between emit and checkpoint save, so consumers must be idempotent on
 * ({@code eventId}, {@code removed}, {@code blockHash}). A retraction ({@code removed=true}) may be followed by
 * a re-emission with the SAME eventId when the transaction is re-included by the new fork.
 */
@FunctionalInterface
public interface ChainEventSink {
    void emit(ChainEvent event);
}
