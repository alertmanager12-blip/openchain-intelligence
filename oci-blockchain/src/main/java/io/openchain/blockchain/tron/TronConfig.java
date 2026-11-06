package io.openchain.blockchain.tron;

import io.openchain.core.model.Network;
import java.time.Duration;

/** @param useSolidityNode read the solidified head from /walletsolidity/getnowblock, else head - confirmations */
public record TronConfig(int confirmations, boolean useSolidityNode, Duration pollInterval) {
    public static TronConfig defaults() { return new TronConfig(Network.TRON.defaultConfirmations(), true, Duration.ofSeconds(3)); }
}
