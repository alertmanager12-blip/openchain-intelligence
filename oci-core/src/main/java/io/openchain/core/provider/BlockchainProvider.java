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

    List<Balance> getAddressBalance(Address address);

    /** Push subscription when supported; otherwise implementations poll. Returns a handle to cancel. */
    Subscription subscribeToEvents(Consumer<BlockRef> onNewHead);

    NetworkStatus getNetworkStatus();

    interface Subscription extends AutoCloseable { @Override void close(); }

    record TransactionReceipt(ChainTransaction transaction, List<TokenTransfer> transfers, boolean success) {}
}
