package io.openchain.core.provider;

import io.openchain.core.model.Token;
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

    /**
     * Balance of {@code token} (native coin when {@link Token#isNative()}) held by {@code address} at the END of block
     * {@code atBlock}, in base units. Empty when the provider cannot answer for that block (no historical state, e.g.
     * TronGrid). Errors (rate limits, pruned state) are thrown as {@link ProviderException}; callers must then treat the
     * balance as unknown, never estimate it.
     */
    default Optional<java.math.BigInteger> getBalanceAt(Address address, Token token, long atBlock) { return Optional.empty(); }

    /** Push subscription when supported; otherwise implementations poll. Returns a handle to cancel. */
    Subscription subscribeToEvents(Consumer<BlockRef> onNewHead);

    NetworkStatus getNetworkStatus();

    interface Subscription extends AutoCloseable { @Override void close(); }

    record TransactionReceipt(ChainTransaction transaction, List<TokenTransfer> transfers, boolean success) {}
}
