package io.openchain.blockchain.transport;

import java.time.Duration;

/** Abstraction over {@link Thread#sleep} so backoff and rate limiting are testable without real waits. */
@FunctionalInterface
public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;

    Sleeper SYSTEM = d -> { if (!d.isNegative() && !d.isZero()) Thread.sleep(d); };
}
