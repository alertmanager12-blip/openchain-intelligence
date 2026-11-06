package io.openchain.blockchain.token;

import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory registry pre-loaded with official USDT/USDC deployments; extensible via {@link #register}. */
public final class DefaultTokenRegistry implements TokenRegistry {
    private final Map<Network, Map<String, Token>> tokens = new ConcurrentHashMap<>();

    public DefaultTokenRegistry() { this(true); }

    public DefaultTokenRegistry(boolean withDefaults) {
        if (!withDefaults) return;
        register(new Token(Network.ETHEREUM, "0xdac17f958d2ee523a2206206994597c13d831ec7", "USDT", 6));
        register(new Token(Network.ETHEREUM, "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "USDC", 6));
        register(new Token(Network.BSC, "0x55d398326f99059ff775485246999027b3197955", "USDT", 18));
        register(new Token(Network.BSC, "0x8ac76a51cc950d9822d68b83fe1ad97b32cd580d", "USDC", 18));
        register(new Token(Network.POLYGON, "0xc2132d05d31c914a87c6611c10748aeb04b58e8f", "USDT", 6));
        register(new Token(Network.POLYGON, "0x3c499c542cef5e3811e1192ce70d8cc03d5c3359", "USDC", 6));
        register(new Token(Network.POLYGON, "0x2791bca1f2de4661ed88a30c99a7a9449aa84174", "USDC.e", 6));
        register(new Token(Network.TRON, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "USDT", 6));
        register(new Token(Network.TRON, "TEkxiTehnzSmSe2XqrBj4w32RUN966rdz8", "USDC", 6));
    }

    public DefaultTokenRegistry register(Token token) {
        if (token.isNative()) throw new IllegalArgumentException("native tokens are not registered");
        Token t = new Token(token.network(), TokenRegistry.canonical(token.network(), token.contract()),
                token.symbol(), token.decimals());
        tokens.computeIfAbsent(t.network(), n -> new ConcurrentHashMap<>()).put(t.contract(), t);
        return this;
    }

    @Override public Optional<Token> find(Network network, String contract) {
        if (contract == null) return Optional.empty();
        return Optional.ofNullable(tokens.getOrDefault(network, Map.of()).get(TokenRegistry.canonical(network, contract)));
    }

    @Override public Collection<Token> known(Network network) {
        return List.copyOf(tokens.getOrDefault(network, Map.of()).values());
    }
}
