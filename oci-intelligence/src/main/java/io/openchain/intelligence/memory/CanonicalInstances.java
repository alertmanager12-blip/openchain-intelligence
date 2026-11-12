package io.openchain.intelligence.memory;

import io.openchain.core.model.Address;
import io.openchain.core.model.Token;
import io.openchain.intelligence.flow.ValueFlow;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

/**
 * Shares one instance per equal {@link Token} and {@link Address} among stored flows (docs/KEY_COMPACTION_ANALYSIS.md,
 * option A). Decoders create a new token and new addresses for every event; a flow kept in memory then holds its own copy
 * of values held a hundred thousand times elsewhere.
 *
 * <p>Only object identity changes: a canonical instance is substituted for an instance {@link Object#equals equal} to it
 * (records: every component), so equality, hashing, identification, deduplication and network isolation are unchanged.
 * Two tokens with the same symbol but another contract, network or decimals are different tokens and stay different.
 *
 * <p>Bounded memory, no global unbounded intern table:
 * <ul>
 *   <li>tokens: at most {@link #DEFAULT_TOKEN_CAPACITY} entries, least recently used forgotten first (a forgotten token
 *       only stops being shared; nothing else changes);</li>
 *   <li>addresses: no table of their own; the canonical instance is the one already held by the wallet's profile
 *       ({@code addressSource}), so it lives exactly as long as that profile (bounded and trimmed by ADR-0024).</li>
 * </ul>
 * Thread-safe.
 */
public final class CanonicalInstances {
    public static final int DEFAULT_TOKEN_CAPACITY = 4_096;

    /** Shares nothing: every flow is returned unchanged (reference behaviour for differential tests). */
    public static final CanonicalInstances NONE = new CanonicalInstances(0, UnaryOperator.identity(), Object::hashCode);

    /** Key with an injectable hash (tests force every hash equal); equality is always the token's full equality. */
    private record Key(Token token, int hash) {
        @Override public boolean equals(Object o) { return o instanceof Key k && token.equals(k.token); }
        @Override public int hashCode() { return hash; }
    }

    private final int tokenCapacity;
    private final UnaryOperator<Address> addressSource;
    private final ToIntFunction<Object> hash;
    private final Map<Key, Token> tokens;
    private long tokenHits, tokenMisses, addressesShared, flowsRebuilt;

    /**
     * @param tokenCapacity maximum number of canonical tokens (0 disables token sharing)
     * @param addressSource canonical instance of an address, or the address itself when none is held
     */
    public CanonicalInstances(int tokenCapacity, UnaryOperator<Address> addressSource) {
        this(tokenCapacity, addressSource, Object::hashCode);
    }

    /** Test constructor: {@code hash} replaces the tokens' hash code (e.g. a constant to force every collision). */
    public CanonicalInstances(int tokenCapacity, UnaryOperator<Address> addressSource, ToIntFunction<Object> hash) {
        this.tokenCapacity = tokenCapacity;
        this.addressSource = addressSource;
        this.hash = hash;
        this.tokens = new LinkedHashMap<>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Key, Token> e) { return size() > CanonicalInstances.this.tokenCapacity; }
        };
    }

    /** The canonical instance equal to {@code t} (registering {@code t} when none is held). */
    public Token token(Token t) {
        if (t == null || tokenCapacity <= 0) return t;
        synchronized (tokens) {
            Token c = tokens.putIfAbsent(new Key(t, hash.applyAsInt(t)), t);
            if (c == null) { tokenMisses++; return t; }
            tokenHits++;
            return c;
        }
    }

    public Address address(Address a) {
        if (a == null) return null;
        Address c = addressSource.apply(a);
        if (c == null || !c.equals(a)) return a; // defensive: never substitute a different value
        return c;
    }

    /** {@code f} itself when its parts are already canonical, otherwise an equal flow built on the canonical instances. */
    public ValueFlow flow(ValueFlow f) {
        if (this == NONE) return f;
        Address from = address(f.from());
        Address to = f.to().equals(f.from()) ? from : address(f.to());
        Token token = token(f.token());
        if (from == f.from() && to == f.to() && token == f.token()) return f;
        synchronized (tokens) {
            if (from != f.from()) addressesShared++;
            if (to != f.to()) addressesShared++;
            flowsRebuilt++;
        }
        return new ValueFlow(f.eventId(), f.network(), f.txHash(), f.timestamp(), from, to, token, f.rawAmount());
    }

    public record Stats(int tokens, int tokenCapacity, long tokenHits, long tokenMisses, long addressesShared, long flowsRebuilt) {}

    public Stats stats() {
        synchronized (tokens) { return new Stats(tokens.size(), tokenCapacity, tokenHits, tokenMisses, addressesShared, flowsRebuilt); }
    }
}
