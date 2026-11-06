package io.openchain.blockchain.transport;

import java.time.Duration;

/**
 * Resilience settings of one endpoint.
 *
 * @param maxAttempts       total attempts including the first one (>= 1)
 * @param initialBackoff    backoff before the 2nd attempt; doubles each attempt
 * @param maxBackoff        upper bound of a single backoff
 * @param jitter            random fraction in [0,1] applied as +/- around the computed backoff
 * @param permitsPerSecond  token-bucket refill rate (<= 0 disables rate limiting)
 * @param burst             token-bucket capacity
 * @param failureThreshold  consecutive failures that open the circuit
 * @param openDuration      time the circuit stays open before a half-open probe
 */
public record ResilienceConfig(int maxAttempts, Duration initialBackoff, Duration maxBackoff, double jitter,
                               double permitsPerSecond, int burst, int failureThreshold, Duration openDuration) {
    public ResilienceConfig {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        if (jitter < 0 || jitter > 1) throw new IllegalArgumentException("jitter must be in [0,1]");
        if (burst < 1) throw new IllegalArgumentException("burst must be >= 1");
        if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold must be >= 1");
    }

    public static ResilienceConfig defaults() {
        return new ResilienceConfig(4, Duration.ofMillis(200), Duration.ofSeconds(5), 0.2,
                10, 20, 5, Duration.ofSeconds(30));
    }
}
