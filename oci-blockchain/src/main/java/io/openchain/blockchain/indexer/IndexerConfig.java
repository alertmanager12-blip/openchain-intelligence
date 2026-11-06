package io.openchain.blockchain.indexer;

import io.openchain.core.model.Network;

/**
 * @param startBlock       first block to index when no checkpoint exists
 * @param confirmations    only blocks with number <= head - confirmations are indexed
 * @param windowSize       reorg rollback window (defaults to {@link Network#maxReorgDepth()})
 * @param maxBlocksPerPoll bound on work per {@link BlockIndexer#poll()}
 */
public record IndexerConfig(String indexerId, long startBlock, int confirmations, int windowSize, int maxBlocksPerPoll) {
    public IndexerConfig {
        if (startBlock < 0 || confirmations < 0 || windowSize < 1 || maxBlocksPerPoll < 1) {
            throw new IllegalArgumentException("invalid indexer config");
        }
    }

    public static IndexerConfig of(String indexerId, Network network, long startBlock) {
        return new IndexerConfig(indexerId, startBlock, network.defaultConfirmations(), network.maxReorgDepth(), 100);
    }
}
