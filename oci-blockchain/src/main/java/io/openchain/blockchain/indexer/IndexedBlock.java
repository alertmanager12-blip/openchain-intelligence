package io.openchain.blockchain.indexer;

import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.BlockRef;
import java.util.List;

/** A block kept in the reorg window, with the events emitted for it (needed to retract them). */
public record IndexedBlock(BlockRef block, List<ChainEvent> events) {
    public IndexedBlock { events = List.copyOf(events); }
}
