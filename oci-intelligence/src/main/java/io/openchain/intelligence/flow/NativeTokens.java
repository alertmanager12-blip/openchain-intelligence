package io.openchain.intelligence.flow;

import io.openchain.core.model.Network;
import io.openchain.core.model.Token;

/** Native coin descriptors per network (used to turn native transaction value into a {@link ValueFlow}). */
public final class NativeTokens {
    private NativeTokens() {}

    public static Token of(Network n) {
        return Token.nativeOf(n);
    }
}
