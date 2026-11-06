package io.openchain.core.model;

import java.util.List;
import java.util.stream.Stream;

/**
 * Everything the indexer ingests from a block range.
 *
 * @param nativeTransfers successful transactions carrying a non-zero native value (ETH, BNB, POL, TRX), one per
 *                        transaction; failed and zero-value transactions are never included. Value moved by
 *                        internal transactions (contract-to-address calls, traces) is NOT covered.
 * @param approvals       Approval logs of fungible tokens (all tokens; the indexer filters unknown ones by default)
 * @param freezes         blacklist events of known stablecoin issuers' official contracts
 * @param adminChanges    ownership / proxy admin / implementation changes of any contract (the indexer keeps only the
 *                        contracts the operator watches by default)
 */
public record BlockActivity(List<TokenTransfer> tokenTransfers, List<ChainTransaction> nativeTransfers,
                            List<TokenApproval> approvals, List<TokenFreeze> freezes, List<ContractAdminChange> adminChanges) {

    public static final BlockActivity EMPTY = new BlockActivity(List.of(), List.of(), List.of(), List.of(), List.of());

    public BlockActivity {
        tokenTransfers = List.copyOf(tokenTransfers);
        nativeTransfers = List.copyOf(nativeTransfers);
        approvals = List.copyOf(approvals);
        freezes = List.copyOf(freezes);
        adminChanges = List.copyOf(adminChanges);
    }

    /** Activity without admin changes (providers that do not decode them). */
    public BlockActivity(List<TokenTransfer> tokenTransfers, List<ChainTransaction> nativeTransfers,
                         List<TokenApproval> approvals, List<TokenFreeze> freezes) {
        this(tokenTransfers, nativeTransfers, approvals, freezes, List.of());
    }

    /** Activity of a provider that only knows token transfers. */
    public static BlockActivity ofTransfers(List<TokenTransfer> transfers) {
        return new BlockActivity(transfers, List.of(), List.of(), List.of(), List.of());
    }

    /** Block hashes every item claims to belong to (nulls skipped): used to detect a chain change mid-read. */
    public Stream<String> blockHashes() {
        return Stream.of(tokenTransfers.stream().map(TokenTransfer::blockHash), nativeTransfers.stream().map(ChainTransaction::blockHash),
                approvals.stream().map(TokenApproval::blockHash), freezes.stream().map(TokenFreeze::blockHash),
                adminChanges.stream().map(ContractAdminChange::blockHash))
                .flatMap(s -> s).filter(java.util.Objects::nonNull);
    }

    public int size() { return tokenTransfers.size() + nativeTransfers.size() + approvals.size() + freezes.size() + adminChanges.size(); }
}
