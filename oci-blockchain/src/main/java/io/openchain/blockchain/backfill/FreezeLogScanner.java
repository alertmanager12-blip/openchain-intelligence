package io.openchain.blockchain.backfill;

import io.openchain.core.model.Network;
import io.openchain.core.model.TokenFreeze;
import java.util.List;

/**
 * Historical scan of stablecoin issuer blacklist events ({@code StablecoinFreezeSources}) over a block range, for
 * {@link FreezeBackfill}. Implemented by the EVM ({@code eth_getLogs}) and TRON (TronGrid contract events) adapters.
 */
public interface FreezeLogScanner {

    Network network();

    /** Highest block a backfill may scan: finalized (or head - confirmations), never reorgable blocks. */
    long backfillSafeHead();

    /**
     * Freezes / unfreezes emitted by the official contracts in {@code [fromBlock, toBlock]}, in any order.
     *
     * @throws RangeTooLargeException when the source wants a smaller range (the caller halves it)
     */
    List<TokenFreeze> scanFreezes(long fromBlock, long toBlock);
}
