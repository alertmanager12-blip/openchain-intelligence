package io.openchain.core.model;

import java.time.Instant;

/** Minimal block identity used for confirmation tracking and reorg detection. */
public record BlockRef(Network network, long number, String hash, String parentHash, Instant timestamp) {}
