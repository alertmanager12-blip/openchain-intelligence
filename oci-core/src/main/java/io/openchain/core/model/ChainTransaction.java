package io.openchain.core.model;

import java.math.BigInteger;
import java.time.Instant;

/** A normalized on-chain transaction (network-agnostic). Amounts are raw integer units (no float). */
public record ChainTransaction(
        Network network,
        String hash,
        long blockNumber,
        String blockHash,
        Instant timestamp,
        Address from,
        Address to,
        BigInteger value,
        BigInteger fee,
        boolean success,
        String methodSelector,
        boolean contractInteraction) {}
