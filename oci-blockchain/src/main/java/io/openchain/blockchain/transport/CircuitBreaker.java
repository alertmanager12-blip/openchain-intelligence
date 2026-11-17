package io.openchain.blockchain.transport;

import io.openchain.core.provider.ProviderException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Consecutive-failure circuit breaker. Only retryable (transient) failures count: a 4xx means the request
 * is wrong, not that the endpoint is unhealthy (except 429 and 403, which are rate limits or temporary blocks). When open, calls fail fast with a retryable
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
    /** Why the circuit last opened: the failure that tripped it, for the logs (null while it never opened). */
    private String openReason;
    private String lastOpenCause;

    private static final System.Logger LOG = System.getLogger(CircuitBreaker.class.getName());

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

    /** The failure that last opened the circuit, as "Class: message" (class only for an exception that is not ours). */
    public synchronized String lastOpenCause() { return lastOpenCause; }

    public <T> T execute(Supplier<T> call) {
        acquirePermission();
        try {
            T result = call.get();
            onSuccess();
            return result;
        } catch (ProviderException e) {
            if (e.retryable()) onFailure(e); else onSuccess();
            throw e;
        } catch (RuntimeException | Error e) {
            // an Error too (OutOfMemoryError while reading a response): otherwise a half-open probe that dies this way
            // leaves probeInFlight set, and the endpoint is refused for ever
            onFailure(e);
            throw e;
        }
    }

    private synchronized void acquirePermission() {
        State s = state();
        if (s == State.OPEN) throw new CircuitOpenException("circuit open for endpoint " + name + " (" + openReason + ")");
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

    private synchronized void onFailure(Throwable cause) {
        probeInFlight = false;
        consecutiveFailures++;
        if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
            lastOpenCause = describe(cause);
            openReason = (state == State.HALF_OPEN ? "half-open probe failed" : "after " + consecutiveFailures + " consecutive failure(s)")
                    + ", last: " + lastOpenCause;
            state = State.OPEN;
            openedAt = clock.instant();
            LOG.log(System.Logger.Level.WARNING, "circuit opened for endpoint {0} for {1}: {2}", name, openDuration, openReason);
        }
    }

    /**
     * Our provider exceptions carry the endpoint name and a status, never the URL (which may hold a key); any other
     * exception is named by its class only.
     */
    static String describe(Throwable t) {
        String c = t.getClass().getSimpleName();
        if (!(t instanceof ProviderException) || t.getMessage() == null) return c;
        String m = t.getMessage();
        return c + ": " + (m.length() > 200 ? m.substring(0, 200) + "..." : m);
    }
}
