package io.openchain.core.model;

import java.math.BigInteger;
import java.time.Instant;

public record Balance(Address address, Token token, BigInteger rawAmount, long atBlock, Instant observedAt) {}
