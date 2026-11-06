package io.openchain.core.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;

/** A normalized fungible token movement (TRC20/ERC20/BEP20 Transfer event, or native transfer). */
public record TokenTransfer(
        Network network,
        String txHash,
        int logIndex,
        long blockNumber,
        String blockHash,
        Instant timestamp,
        Address from,
        Address to,
        Token token,
        BigInteger rawAmount) {

    /** Human amount = rawAmount / 10^decimals, exact. */
    public BigDecimal amount() { return new BigDecimal(rawAmount, token.decimals()); }

    /** Globally unique, deterministic id: used as the idempotency/dedup key end to end. */
    public String eventId() { return network.id() + ":" + txHash + ":" + logIndex; }
}
