package io.openchain.intelligence.flow;

import io.openchain.core.model.Network;
import io.openchain.core.model.Token;

/** Native coin descriptors per network (used to turn native transaction value into a {@link ValueFlow}). */
public final class NativeTokens {
    private NativeTokens() {}

    /** One shared descriptor per network (equal to {@link Token#nativeOf(Network)}; was a new instance per call). */
    private static final java.util.Map<Network, Token> BY_NETWORK;
    static {
        java.util.EnumMap<Network, Token> m = new java.util.EnumMap<>(Network.class);
        for (Network n : Network.values()) m.put(n, Token.nativeOf(n));
        BY_NETWORK = java.util.Collections.unmodifiableMap(m);
    }

    public static Token of(Network n) {
        return BY_NETWORK.get(n);
    }
}
