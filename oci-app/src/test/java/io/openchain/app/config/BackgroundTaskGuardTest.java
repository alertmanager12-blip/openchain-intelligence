package io.openchain.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Code review I17: a periodic background task survives an Error as well as an exception (it used to be cancelled silently). */
class BackgroundTaskGuardTest {
    @Test void aPeriodicTaskKeepsRunningAfterAnErrorOrAnException() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            AtomicInteger runs = new AtomicInteger();
            CountDownLatch fourRuns = new CountDownLatch(4);
            scheduler.scheduleWithFixedDelay(Bootstrap.guard("test", () -> {
                int n = runs.incrementAndGet();
                fourRuns.countDown();
                if (n == 1) throw new StackOverflowError("simulated");
                if (n == 2) throw new IllegalStateException("simulated");
            }), 0, 5, TimeUnit.MILLISECONDS);
            assertThat(fourRuns.await(5, TimeUnit.SECONDS)).as("still scheduled after an Error and an exception").isTrue();
        } finally {
            scheduler.shutdownNow();
        }
    }
}
