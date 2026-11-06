package io.openchain.blockchain.transport;

import io.openchain.core.provider.ProviderException;
import java.time.Duration;
import java.util.function.LongSupplier;

/** Thread-safe token bucket. {@link #acquire()} blocks (via {@link Sleeper}) until a permit is available. */
public final class TokenBucketRateLimiter {
    private final double permitsPerNano;
    private final double capacity;
    private final LongSupplier nanoClock;
    private final Sleeper sleeper;
    private double tokens;
    private long lastRefill;

    public TokenBucketRateLimiter(double permitsPerSecond, int burst, LongSupplier nanoClock, Sleeper sleeper) {
        if (permitsPerSecond <= 0) throw new IllegalArgumentException("permitsPerSecond must be > 0");
        this.permitsPerNano = permitsPerSecond / 1_000_000_000d;
        this.capacity = burst;
        this.nanoClock = nanoClock;
        this.sleeper = sleeper;
        this.tokens = burst;
        this.lastRefill = nanoClock.getAsLong();
    }

    public TokenBucketRateLimiter(double permitsPerSecond, int burst) {
        this(permitsPerSecond, burst, System::nanoTime, Sleeper.SYSTEM);
    }

    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1) { tokens -= 1; return true; }
        return false;
    }

    /** Waits for one permit. */
    public void acquire() {
        while (true) {
            long waitNanos;
            synchronized (this) {
                refill();
                if (tokens >= 1) { tokens -= 1; return; }
                waitNanos = (long) Math.ceil((1 - tokens) / permitsPerNano);
            }
            try {
                sleeper.sleep(Duration.ofNanos(Math.max(waitNanos, 1)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProviderException("interrupted while rate limited", false, e);
            }
        }
    }

    synchronized double availableTokens() { refill(); return tokens; }

    private void refill() {
        long now = nanoClock.getAsLong();
        long elapsed = now - lastRefill;
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed * permitsPerNano);
            lastRefill = now;
        }
    }
}
