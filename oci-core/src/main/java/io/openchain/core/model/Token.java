package io.openchain.core.model;

/** A fungible asset. {@code contract} is null for the native coin (ETH, TRX, BNB...). */
public record Token(Network network, String contract, String symbol, int decimals) {
    public boolean isNative() { return contract == null; }
    public static Token nativeOf(Network n, String symbol, int decimals) { return new Token(n, null, symbol, decimals); }
}
