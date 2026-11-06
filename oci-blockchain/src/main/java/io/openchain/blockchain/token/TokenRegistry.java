package io.openchain.blockchain.token;

import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import java.util.Collection;
import java.util.Optional;

/**
 * Token metadata source. Adapters never trust on-chain {@code symbol()} for well-known assets: a scam token can
 * call itself "USDT". Unknown contracts resolve to {@link #UNKNOWN_SYMBOL} with fallback decimals and must be
 * surfaced as unverified ({@link #isKnown}).
 */
public interface TokenRegistry {
    String UNKNOWN_SYMBOL = "UNKNOWN";

    Optional<Token> find(Network network, String contract);

    /** Tokens known for a network (used e.g. for balance lookups). */
    Collection<Token> known(Network network);

    /** Decimals used when metadata is unknown. 0 keeps the amount equal to the raw integer (never inflated). */
    default int fallbackDecimals(Network network) { return 0; }

    default Token resolve(Network network, String contract) {
        return find(network, contract).orElseGet(() ->
                new Token(network, canonical(network, contract), UNKNOWN_SYMBOL, fallbackDecimals(network)));
    }

    default boolean isKnown(Token token) {
        return token.isNative() || find(token.network(), token.contract()).isPresent();
    }

    static String canonical(Network network, String contract) {
        return switch (network) {
            case ETHEREUM, BSC, POLYGON -> contract.toLowerCase();
            default -> contract;
        };
    }
}
