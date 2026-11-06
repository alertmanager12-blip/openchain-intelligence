package io.openchain.blockchain.transport;

import static org.assertj.core.api.Assertions.*;

import io.openchain.blockchain.TestSupport.RecordingSleeper;
import io.openchain.core.provider.ProviderException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ResiliencePrimitivesTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    @Test void tokenBucketAllowsBurstThenRefillsAtRate() {
        AtomicLong nanos = new AtomicLong();
        TokenBucketRateLimiter rl = new TokenBucketRateLimiter(2, 2, nanos::get, d -> {});
        assertThat(rl.tryAcquire()).isTrue();
        assertThat(rl.tryAcquire()).isTrue();
        assertThat(rl.tryAcquire()).isFalse();
        nanos.addAndGet(Duration.ofMillis(500).toNanos()); // 2/s -> 1 token
        assertThat(rl.tryAcquire()).isTrue();
        assertThat(rl.tryAcquire()).isFalse();
        nanos.addAndGet(Duration.ofSeconds(10).toNanos()); // capped at burst
        assertThat(rl.availableTokens()).isEqualTo(2.0);
    }

    @Test void tokenBucketAcquireWaitsForRefill() {
        AtomicLong nanos = new AtomicLong();
        RecordingSleeper rec = new RecordingSleeper();
        TokenBucketRateLimiter rl = new TokenBucketRateLimiter(10, 1, nanos::get, d -> { rec.sleep(d); nanos.addAndGet(d.toNanos()); });
        for (int i = 0; i < 5; i++) rl.acquire();
        assertThat(rec.sleeps).hasSize(4);
        assertThat(rec.sleeps.stream().mapToLong(Duration::toMillis).sum()).isBetween(399L, 401L); // 4 permits at 10/s
    }

    @Test void exponentialBackoffWithCapAndJitterBounds() {
        ResilienceConfig cfg = new ResilienceConfig(5, Duration.ofMillis(100), Duration.ofMillis(500), 0.2, 0, 1, 5, Duration.ofSeconds(1));
        RetryPolicy mid = new RetryPolicy(cfg, d -> {}, () -> 0.5);
        assertThat(mid.backoff(1)).isEqualTo(Duration.ofMillis(100));
        assertThat(mid.backoff(2)).isEqualTo(Duration.ofMillis(200));
        assertThat(mid.backoff(3)).isEqualTo(Duration.ofMillis(400));
        assertThat(mid.backoff(4)).isEqualTo(Duration.ofMillis(500));
        assertThat(new RetryPolicy(cfg, d -> {}, () -> 0.0).backoff(2)).isEqualTo(Duration.ofMillis(160));
        assertThat(new RetryPolicy(cfg, d -> {}, () -> 1.0).backoff(2)).isEqualTo(Duration.ofMillis(240));
    }

    @Test void retriesOnlyRetryableErrorsUpToMaxAttempts() {
        RecordingSleeper rec = new RecordingSleeper();
        RetryPolicy retry = new RetryPolicy(new ResilienceConfig(3, Duration.ofMillis(100), Duration.ofSeconds(1), 0, 0, 1, 5, Duration.ofSeconds(1)), rec, () -> 0.5);
        AtomicInteger calls = new AtomicInteger();
        assertThat(retry.execute(() -> { if (calls.incrementAndGet() < 3) throw new ProviderException("503", true); return "ok"; })).isEqualTo("ok");
        assertThat(rec.sleeps).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200));

        calls.set(0);
        assertThatThrownBy(() -> retry.execute(() -> { calls.incrementAndGet(); throw new ProviderException("400", false); }))
                .isInstanceOf(ProviderException.class);
        assertThat(calls).hasValue(1);

        calls.set(0);
        assertThatThrownBy(() -> retry.execute(() -> { calls.incrementAndGet(); throw new ProviderException("timeout", true); }))
                .hasMessage("timeout");
        assertThat(calls).hasValue(3);
    }

    @Test void circuitBreakerOpensHalfOpensAndCloses() {
        MutableClock clock = new MutableClock();
        CircuitBreaker cb = new CircuitBreaker("ep", 3, Duration.ofSeconds(10), clock);
        Runnable fail = () -> assertThatThrownBy(() -> cb.execute(() -> { throw new ProviderException("5xx", true); }));
        // non-retryable errors do not count
        for (int i = 0; i < 5; i++) assertThatThrownBy(() -> cb.execute(() -> { throw new ProviderException("400", false); }));
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        fail.run(); fail.run(); fail.run();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
        AtomicInteger invoked = new AtomicInteger();
        assertThatThrownBy(() -> cb.execute(invoked::incrementAndGet)).isInstanceOf(CircuitOpenException.class);
        assertThat(invoked).hasValue(0);

        clock.advance(Duration.ofSeconds(10));
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        fail.run(); // failed probe re-opens
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
        clock.advance(Duration.ofSeconds(10));
        assertThat(cb.execute(() -> "probe")).isEqualTo("probe");
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test void retryDoesNotSpinOnOpenCircuit() {
        RecordingSleeper rec = new RecordingSleeper();
        RetryPolicy retry = new RetryPolicy(new ResilienceConfig(5, Duration.ofMillis(100), Duration.ofSeconds(1), 0, 0, 1, 5, Duration.ofSeconds(1)), rec);
        assertThatThrownBy(() -> retry.execute(() -> { throw new CircuitOpenException("open"); }))
                .isInstanceOf(CircuitOpenException.class)
                .satisfies(e -> assertThat(((ProviderException) e).retryable()).isTrue());
        assertThat(rec.sleeps).isEmpty();
    }
}
