package io.openchain.core.model;

/** A fungible asset. {@code contract} is null for the native coin (ETH, TRX, BNB...). */
public record Token(Network network, String contract, String symbol, int decimals) {
    /** Symbol given by token registries to contracts they do not know (their on-chain metadata is never trusted). */
    public static final String UNKNOWN_SYMBOL = "UNKNOWN";

    public boolean isNative() { return contract == null; }

    /**
     * True for a contract no registry vouches for. Anyone can deploy a contract emitting Transfer events with arbitrary
     * {@code from} addresses, so flows of such tokens prove nothing about who sent value.
     */
    public boolean isUnverified() { return !isNative() && UNKNOWN_SYMBOL.equals(symbol); }
    public static Token nativeOf(Network n, String symbol, int decimals) { return new Token(n, null, symbol, decimals); }
}
