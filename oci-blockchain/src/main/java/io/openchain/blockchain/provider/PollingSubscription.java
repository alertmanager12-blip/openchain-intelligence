package io.openchain.blockchain.provider;

import io.openchain.core.model.BlockRef;
import io.openchain.core.provider.BlockchainProvider;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.LongFunction;

/** Head polling on a daemon thread; emits every new block once, in order. Errors are swallowed and retried next tick. */
public final class PollingSubscription implements BlockchainProvider.Subscription {
    private final ScheduledExecutorService exec;
    private long lastSeen = -1;

    public PollingSubscription(String name, Duration interval, LongSupplier head, LongFunction<Optional<BlockRef>> block,
                               Consumer<BlockRef> onNewHead) {
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "oci-head-poller-" + name);
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(() -> {
            try {
                long h = head.getAsLong();
                long from = lastSeen < 0 ? h : lastSeen + 1;
                for (long n = from; n <= h; n++) {
                    Optional<BlockRef> b = block.apply(n);
                    if (b.isEmpty()) break;
                    onNewHead.accept(b.get());
                    lastSeen = n;
                }
            } catch (RuntimeException ignored) {
                // transient provider failure: next tick retries
            }
        }, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override public void close() { exec.shutdownNow(); }
}
