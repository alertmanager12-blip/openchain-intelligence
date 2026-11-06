package io.openchain.alerting.notify.delivery;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff: delay(n) = min(max, initial * multiplier^(n-1)), reduced by up to {@code jitter} (0..1)
 * random fraction to avoid thundering herds. After {@code maxAttempts} failed attempts the delivery is dead-lettered.
 */
public record BackoffPolicy(Duration initial, int multiplier, Duration max, int maxAttempts, double jitter, RandomGenerator random) {

    public BackoffPolicy {
        if (maxAttempts < 1 || multiplier < 1 || jitter < 0 || jitter > 1) throw new IllegalArgumentException("invalid backoff policy");
    }

    public static BackoffPolicy defaults() {
        return new BackoffPolicy(Duration.ofSeconds(10), 2, Duration.ofMinutes(30), 8, 0.2, RandomGenerator.getDefault());
    }

    /** @param attempt number of failed attempts so far (>= 1) */
    public Duration delay(int attempt) {
        Duration d = initial;
        for (int i = 1; i < attempt && d.compareTo(max) < 0; i++) d = d.multipliedBy(multiplier);
        if (d.compareTo(max) > 0) d = max;
        if (jitter > 0) d = d.minusMillis((long) (d.toMillis() * jitter * random.nextDouble()));
        return d;
    }
}
