package io.openchain.core.provider;

import io.openchain.core.model.*;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Network-agnostic access to a chain. One implementation per network/data-source pair
 * (e.g. TronGridProvider, EvmJsonRpcProvider). Business logic MUST depend on this SPI only.
 * Implementations must be thread-safe, bounded (timeouts) and throw {@link ProviderException} on failure.
 */
public interface BlockchainProvider {

    /** Stable id, e.g. "tron-trongrid", "ethereum-rpc-1". */
    String id();

    Network network();

    Optional<BlockRef> getBlock(long number);

    Optional<ChainTransaction> getTransaction(String hash);

    /** Token transfers + status from the receipt/logs of one transaction. */
    Optional<TransactionReceipt> getTransactionReceipt(String hash);

    Page<ChainTransaction> getAddressTransactions(Address address, String cursor, int limit);

    /** All fungible token transfers in the inclusive block range. */
    List<TokenTransfer> getTokenTransfers(long fromBlock, long toBlock);

    /**
     * Token transfers, successful non-zero native transfers, token approvals and stablecoin freezes in the inclusive
     * block range. The default covers token transfers only (providers without the richer decoding).
     */
    default BlockActivity getBlockActivity(long fromBlock, long toBlock) {
        return BlockActivity.ofTransfers(getTokenTransfers(fromBlock, toBlock));
    }

    /**
     * Whether {@code address} holds contract code, as of block {@code atBlock} when the source can answer for a past
     * block (EVM {@code eth_getCode}) or as of now otherwise (TRON). Empty when this provider cannot tell (default).
     * Implementations cache answers; a failed lookup throws {@link ProviderException}.
     */
    default Optional<Boolean> isContract(Address address, long atBlock) { return Optional.empty(); }

    List<Balance> getAddressBalance(Address address);

    /** Push subscription when supported; otherwise implementations poll. Returns a handle to cancel. */
    Subscription subscribeToEvents(Consumer<BlockRef> onNewHead);

    NetworkStatus getNetworkStatus();

    interface Subscription extends AutoCloseable { @Override void close(); }

    record TransactionReceipt(ChainTransaction transaction, List<TokenTransfer> transfers, boolean success) {}
}
