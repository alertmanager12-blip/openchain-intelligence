package io.openchain.blockchain.transport;

import io.openchain.core.provider.ProviderException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/** Retries retryable {@link ProviderException}s with exponential backoff and jitter. */
public final class RetryPolicy {
    private final ResilienceConfig config;
    private final Sleeper sleeper;
    private final DoubleSupplier random;

    public RetryPolicy(ResilienceConfig config, Sleeper sleeper) {
        this(config, sleeper, () -> ThreadLocalRandom.current().nextDouble());
    }

    public RetryPolicy(ResilienceConfig config, Sleeper sleeper, DoubleSupplier random) {
        this.config = config;
        this.sleeper = sleeper;
        this.random = random;
    }

    /** Backoff before attempt {@code attempt+1} (attempt is 1-based); exposed for tests. */
    public Duration backoff(int attempt) {
        long base = config.initialBackoff().toMillis();
        long exp = base * (1L << Math.min(attempt - 1, 20));
        long capped = Math.min(exp, config.maxBackoff().toMillis());
        double factor = 1 + config.jitter() * (2 * random.getAsDouble() - 1);
        return Duration.ofMillis(Math.max(0, Math.round(capped * factor)));
    }

    public <T> T execute(Supplier<T> call) {
        ProviderException last = null;
        for (int attempt = 1; attempt <= config.maxAttempts(); attempt++) {
            try {
                return call.get();
            } catch (ProviderException e) {
                last = e;
                if (!e.retryable() || e instanceof CircuitOpenException || attempt == config.maxAttempts()) throw e;
                Duration wait = e instanceof RetryAfterException ra && ra.retryAfter() != null
                        ? max(ra.retryAfter(), backoff(attempt)) : backoff(attempt);
                try {
                    sleeper.sleep(min(wait, config.maxBackoff()));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ProviderException("interrupted during retry backoff", false, ie);
                }
            }
        }
        throw last; // unreachable
    }

    private static Duration max(Duration a, Duration b) { return a.compareTo(b) >= 0 ? a : b; }
    private static Duration min(Duration a, Duration b) { return a.compareTo(b) <= 0 ? a : b; }

    /** Retryable failure carrying a server-provided Retry-After hint. */
    public static final class RetryAfterException extends ProviderException {
        private final Duration retryAfter;
        public RetryAfterException(String message, Duration retryAfter) { super(message, true); this.retryAfter = retryAfter; }
        public Duration retryAfter() { return retryAfter; }
    }
}
