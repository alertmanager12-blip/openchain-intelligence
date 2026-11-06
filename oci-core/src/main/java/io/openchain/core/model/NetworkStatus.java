package io.openchain.core.model;

import java.time.Instant;

public record NetworkStatus(Network network, long headBlock, long finalizedBlock, boolean healthy,
                            String providerId, long latencyMillis, Instant checkedAt, String detail) {}
