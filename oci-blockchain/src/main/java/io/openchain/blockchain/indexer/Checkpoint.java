package io.openchain.blockchain.indexer;

import io.openchain.core.model.Network;
import java.util.List;

/**
 * Durable indexer state.
 *
 * @param lastBlock last fully processed block ({@code startBlock - 1} before the first one)
 * @param window    most recent processed blocks, oldest first, bounded by the reorg window size
 */
public record Checkpoint(Network network, long lastBlock, List<IndexedBlock> window) {
    public Checkpoint { window = List.copyOf(window); }
}
