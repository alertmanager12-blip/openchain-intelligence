package io.openchain.blockchain;

import io.openchain.blockchain.transport.ResilienceConfig;
import io.openchain.blockchain.transport.RpcEndpoint;
import io.openchain.blockchain.transport.Sleeper;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class TestSupport {
    private TestSupport() {}

    /** Sleeper recording requested waits without actually sleeping. */
    public static final class RecordingSleeper implements Sleeper {
        public final List<Duration> sleeps = new CopyOnWriteArrayList<>();
        @Override public void sleep(Duration d) { sleeps.add(d); }
    }

    public static ResilienceConfig fastResilience(int attempts, int failureThreshold) {
        return new ResilienceConfig(attempts, Duration.ofMillis(100), Duration.ofSeconds(5), 0.0, 0, 1,
                failureThreshold, Duration.ofSeconds(30));
    }

    public static RpcEndpoint endpoint(String name, String url, ResilienceConfig r) {
        return new RpcEndpoint(name, URI.create(url), Map.of("X-Api-Key", "secret"), Duration.ofSeconds(2), Duration.ofSeconds(2), r);
    }
}
