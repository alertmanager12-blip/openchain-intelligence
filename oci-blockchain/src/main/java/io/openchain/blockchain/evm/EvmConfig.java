package io.openchain.blockchain.evm;

import io.openchain.core.model.Network;
import java.time.Duration;

/**
 * @param confirmations       head-confirmations used as finality fallback when the "finalized" tag is unavailable
 * @param useFinalizedTag     query {@code eth_getBlockByNumber("finalized")} first
 * @param maxLogRange         max blocks per eth_getLogs call (providers cap ranges); larger ranges are chunked
 * @param historyChunkSize    blocks per eth_getLogs chunk of an address history scan at the start of a run (ADR-0020)
 * @param historyMinChunkSize smallest history chunk before a "range too large" error is given up
 */
public record EvmConfig(String nativeSymbol, int nativeDecimals, int confirmations, boolean useFinalizedTag,
                        Duration pollInterval, int maxLogRange, int historyChunkSize, int historyMinChunkSize) {
    public static final int DEFAULT_HISTORY_CHUNK = 10_000;
    public static final int DEFAULT_HISTORY_MIN_CHUNK = 50;

    public EvmConfig {
        if (historyMinChunkSize < 1 || historyChunkSize < historyMinChunkSize) {
            throw new IllegalArgumentException("invalid history chunk sizes: " + historyChunkSize + "/" + historyMinChunkSize);
        }
    }

    /** Backward-compatible form: default history chunk sizes. */
    public EvmConfig(String nativeSymbol, int nativeDecimals, int confirmations, boolean useFinalizedTag,
                     Duration pollInterval, int maxLogRange) {
        this(nativeSymbol, nativeDecimals, confirmations, useFinalizedTag, pollInterval, maxLogRange,
                DEFAULT_HISTORY_CHUNK, DEFAULT_HISTORY_MIN_CHUNK);
    }

    public static EvmConfig defaults(Network network) {
        String symbol = switch (network) {
            case ETHEREUM -> "ETH";
            case BSC -> "BNB";
            case POLYGON -> "POL";
            default -> throw new IllegalArgumentException(network + " is not an EVM network");
        };
        return new EvmConfig(symbol, 18, network.defaultConfirmations(), true, Duration.ofSeconds(3), 500);
    }

    public EvmConfig withHistoryChunks(int chunkSize, int minChunkSize) {
        return new EvmConfig(nativeSymbol, nativeDecimals, confirmations, useFinalizedTag, pollInterval, maxLogRange,
                chunkSize, minChunkSize);
    }
}
