package io.openchain.blockchain.transport;

import io.openchain.core.provider.ProviderException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Consecutive-failure circuit breaker. Only retryable (transient) failures count: a 4xx means the request
 * is wrong, not that the endpoint is unhealthy. When open, calls fail fast with a retryable
 * {@link ProviderException} so that {@link HttpJsonTransport} fails over to its next endpoint.
 */
public final class CircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final int failureThreshold;
    private final Duration openDuration;
    private final Clock clock;
    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openedAt;
    private boolean probeInFlight;

    public CircuitBreaker(String name, int failureThreshold, Duration openDuration, Clock clock) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
        this.clock = clock;
    }

    public synchronized State state() {
        if (state == State.OPEN && !clock.instant().isBefore(openedAt.plus(openDuration))) state = State.HALF_OPEN;
        return state;
    }

    public <T> T execute(Supplier<T> call) {
        acquirePermission();
        try {
            T result = call.get();
            onSuccess();
            return result;
        } catch (ProviderException e) {
            if (e.retryable()) onFailure(); else onSuccess();
            throw e;
        } catch (RuntimeException e) {
            onFailure();
            throw e;
        }
    }

    private synchronized void acquirePermission() {
        State s = state();
        if (s == State.OPEN) throw new CircuitOpenException("circuit open for endpoint " + name);
        if (s == State.HALF_OPEN) {
            if (probeInFlight) throw new CircuitOpenException("circuit half-open (probe in flight) for endpoint " + name);
            probeInFlight = true;
        }
    }

    private synchronized void onSuccess() {
        consecutiveFailures = 0;
        probeInFlight = false;
        state = State.CLOSED;
    }

    private synchronized void onFailure() {
        probeInFlight = false;
        consecutiveFailures++;
        if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            openedAt = clock.instant();
        }
    }
}
