package io.openchain.blockchain.indexer;

import io.openchain.blockchain.token.TokenRegistry;
import io.openchain.core.model.Network;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @param startBlock       first block to index when no checkpoint exists
 * @param confirmations    only blocks with number <= head - confirmations are indexed
 * @param windowSize       reorg rollback window (defaults to {@link Network#maxReorgDepth()})
 * @param maxBlocksPerPoll bound on work per {@link BlockIndexer#poll()}
 * @param approvalsOfUnknownTokens also emit APPROVAL events of tokens the TokenRegistry does not know (default false:
 *                         anyone can deploy a token and emit approvals, and they are high-volume noise)
 * @param watchedContracts contracts whose admin changes ({@code CONTRACT_ADMIN_CHANGE}) are emitted in addition to the
 *                         token registry's contracts (canonical form; EVM lowercase)
 * @param adminChangesOfAllContracts emit admin changes of every contract (default false: ownership transfers of
 *                         arbitrary contracts are frequent and mostly irrelevant to the operator)
 */
public record IndexerConfig(String indexerId, long startBlock, int confirmations, int windowSize, int maxBlocksPerPoll,
                            boolean approvalsOfUnknownTokens, Set<String> watchedContracts, boolean adminChangesOfAllContracts) {
    public IndexerConfig(String indexerId, long startBlock, int confirmations, int windowSize, int maxBlocksPerPoll) {
        this(indexerId, startBlock, confirmations, windowSize, maxBlocksPerPoll, false);
    }

    public IndexerConfig(String indexerId, long startBlock, int confirmations, int windowSize, int maxBlocksPerPoll,
                         boolean approvalsOfUnknownTokens) {
        this(indexerId, startBlock, confirmations, windowSize, maxBlocksPerPoll, approvalsOfUnknownTokens, Set.of(), false);
    }

    public IndexerConfig withApprovalsOfUnknownTokens(boolean include) {
        return new IndexerConfig(indexerId, startBlock, confirmations, windowSize, maxBlocksPerPoll, include, watchedContracts,
                adminChangesOfAllContracts);
    }

    /** Watch list of contracts for admin changes (canonicalised for {@code network}). */
    public IndexerConfig withWatchedContracts(Network network, Set<String> contracts) {
        Set<String> c = contracts == null ? Set.of() : contracts.stream().filter(s -> s != null && !s.isBlank())
                .map(s -> TokenRegistry.canonical(network, s.strip())).collect(Collectors.toUnmodifiableSet());
        return new IndexerConfig(indexerId, startBlock, confirmations, windowSize, maxBlocksPerPoll, approvalsOfUnknownTokens, c,
                adminChangesOfAllContracts);
    }

    public IndexerConfig withAdminChangesOfAllContracts(boolean all) {
        return new IndexerConfig(indexerId, startBlock, confirmations, windowSize, maxBlocksPerPoll, approvalsOfUnknownTokens,
                watchedContracts, all);
    }

    public IndexerConfig {
        if (startBlock < 0 || confirmations < 0 || windowSize < 1 || maxBlocksPerPoll < 1) {
            throw new IllegalArgumentException("invalid indexer config");
        }
        watchedContracts = watchedContracts == null ? Set.of() : Set.copyOf(watchedContracts);
    }

    public static IndexerConfig of(String indexerId, Network network, long startBlock) {
        return new IndexerConfig(indexerId, startBlock, network.defaultConfirmations(), network.maxReorgDepth(), 100);
    }
}
