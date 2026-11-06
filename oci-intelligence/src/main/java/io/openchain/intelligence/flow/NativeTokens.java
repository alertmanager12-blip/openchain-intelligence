package io.openchain.intelligence.flow;

import io.openchain.core.model.Network;
import io.openchain.core.model.Token;

/** Native coin descriptors per network (used to turn native transaction value into a {@link ValueFlow}). */
public final class NativeTokens {
    private NativeTokens() {}

    public static Token of(Network n) {
        return switch (n) {
            case TRON -> Token.nativeOf(n, "TRX", 6);
            case ETHEREUM -> Token.nativeOf(n, "ETH", 18);
            case BSC -> Token.nativeOf(n, "BNB", 18);
            case POLYGON -> Token.nativeOf(n, "POL", 18);
            case BITCOIN -> Token.nativeOf(n, "BTC", 8);
            case SOLANA -> Token.nativeOf(n, "SOL", 9);
        };
    }
}
