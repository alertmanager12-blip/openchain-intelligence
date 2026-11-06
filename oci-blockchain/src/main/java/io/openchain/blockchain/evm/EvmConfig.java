package io.openchain.blockchain.evm;

import io.openchain.core.model.Network;
import java.time.Duration;

/**
 * @param confirmations   head-confirmations used as finality fallback when the "finalized" tag is unavailable
 * @param useFinalizedTag query {@code eth_getBlockByNumber("finalized")} first
 * @param maxLogRange     max blocks per eth_getLogs call (providers cap ranges); larger ranges are chunked
 */
public record EvmConfig(String nativeSymbol, int nativeDecimals, int confirmations, boolean useFinalizedTag,
                        Duration pollInterval, int maxLogRange) {
    public static EvmConfig defaults(Network network) {
        String symbol = switch (network) {
            case ETHEREUM -> "ETH";
            case BSC -> "BNB";
            case POLYGON -> "POL";
            default -> throw new IllegalArgumentException(network + " is not an EVM network");
        };
        return new EvmConfig(symbol, 18, network.defaultConfirmations(), true, Duration.ofSeconds(3), 500);
    }
}
