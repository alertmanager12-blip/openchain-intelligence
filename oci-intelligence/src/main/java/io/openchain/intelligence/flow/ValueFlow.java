package io.openchain.intelligence.flow;

import io.openchain.core.model.Address;
import io.openchain.core.model.ChainTransaction;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.core.model.TokenTransfer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One actual movement of value (token transfer or native value of a transaction) between two addresses.
 * This is the only edge type used for exposure propagation: no value flow, no propagation.
 *
 * @param eventId deterministic idempotency key (same as {@link TokenTransfer#eventId()} for tokens,
 *                {@code network:hash:native} for native value)
 */
public record ValueFlow(String eventId, Network network, String txHash, Instant timestamp,
                        Address from, Address to, Token token, BigInteger rawAmount) {

    public ValueFlow {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(txHash, "txHash");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(rawAmount, "rawAmount");
        if (rawAmount.signum() < 0) throw new IllegalArgumentException("negative amount");
    }

    /** Exact human amount. */
    public BigDecimal amount() { return new BigDecimal(rawAmount, token.decimals()); }

    /** Stable token key: {@code network:contract} or {@code network:native:SYMBOL}. */
    public String tokenKey() { return tokenKey(token); }

    public static String tokenKey(Token t) {
        return t.network().id() + ":" + (t.isNative() ? "native:" + t.symbol() : t.contract().toLowerCase());
    }

    public boolean involves(Address a) { return from.equals(a) || to.equals(a); }

    public Address counterparty(Address self) { return from.equals(self) ? to : from; }

    public static ValueFlow of(TokenTransfer t) {
        return new ValueFlow(t.eventId(), t.network(), t.txHash(), t.timestamp(), t.from(), t.to(), t.token(), t.rawAmount());
    }

    /**
     * Native value carried by a successful transaction, if any.
     *
     * @param nativeToken native coin descriptor of the network (symbol/decimals)
     */
    public static Optional<ValueFlow> ofNative(ChainTransaction tx, Token nativeToken) {
        if (!tx.success() || tx.to() == null || tx.value() == null || tx.value().signum() <= 0) return Optional.empty();
        return Optional.of(new ValueFlow(nativeEventId(tx), tx.network(), tx.hash(), tx.timestamp(),
                tx.from(), tx.to(), nativeToken, tx.value()));
    }

    public static String nativeEventId(ChainTransaction tx) { return tx.network().id() + ":" + tx.hash() + ":native"; }
}
