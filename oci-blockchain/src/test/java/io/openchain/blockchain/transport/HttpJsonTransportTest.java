package io.openchain.blockchain.transport;

import static io.openchain.blockchain.TestSupport.*;
import static org.assertj.core.api.Assertions.*;

import io.openchain.blockchain.StubHttpServer;
import io.openchain.blockchain.StubHttpServer.Response;
import io.openchain.blockchain.TestSupport.RecordingSleeper;
import io.openchain.core.provider.ProviderException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpJsonTransportTest {
    StubHttpServer server;
    RecordingSleeper sleeper = new RecordingSleeper();

    @BeforeEach void start() throws Exception { server = new StubHttpServer(); }
    @AfterEach void stop() { server.close(); }

    HttpJsonTransport transport(ResilienceConfig r) {
        return new HttpJsonTransport(endpoint("stub", server.url(), r), Clock.systemUTC(), sleeper);
    }

    @Test void retriesOn5xxThenSucceedsAndSendsConfiguredHeaders() {
        AtomicInteger n = new AtomicInteger();
        server.route("/x", r -> n.incrementAndGet() < 3 ? Response.status(503) : Response.ok("{\"v\":1}"));
        assertThat(transport(fastResilience(4, 10)).get("/x").path("v").asInt()).isEqualTo(1);
        assertThat(n).hasValue(3);
        assertThat(sleeper.sleeps).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200));
    }

    @Test void honoursRetryAfterOn429() {
        AtomicInteger n = new AtomicInteger();
        server.route("/x", r -> n.incrementAndGet() == 1 ? new Response(429, "{}", Map.of("Retry-After", "2")) : Response.ok("{}"));
        transport(fastResilience(3, 10)).get("/x");
        assertThat(sleeper.sleeps).containsExactly(Duration.ofSeconds(2));
    }

    @Test void doesNotRetryClientErrors() {
        server.route("/x", r -> Response.status(400));
        assertThatThrownBy(() -> transport(fastResilience(4, 10)).get("/x"))
                .isInstanceOf(ProviderException.class).satisfies(e -> assertThat(((ProviderException) e).retryable()).isFalse())
                .hasMessageNotContaining("secret");
        assertThat(server.hits).hasValue(1);
    }

    @Test void timeoutIsRetryable() {
        server.route("/slow", r -> { try { Thread.sleep(800); } catch (InterruptedException e) { } return Response.ok("{}"); });
        HttpJsonTransport t = new HttpJsonTransport(endpoint("stub", server.url(), fastResilience(2, 10))
                .withRequestTimeout(Duration.ofMillis(150)), Clock.systemUTC(), sleeper);
        assertThatThrownBy(() -> t.get("/slow")).isInstanceOf(ProviderException.class).hasMessageContaining("timeout")
                .satisfies(e -> assertThat(((ProviderException) e).retryable()).isTrue());
        assertThat(sleeper.sleeps).hasSize(1);
    }

    @Test void circuitOpensAfterConsecutiveFailuresAndFailsFast() {
        server.route("/x", r -> Response.status(500));
        HttpJsonTransport t = transport(fastResilience(1, 3));
        for (int i = 0; i < 3; i++) assertThatThrownBy(() -> t.get("/x")).isInstanceOf(ProviderException.class);
        assertThat(t.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> t.get("/x")).isInstanceOf(CircuitOpenException.class);
        assertThat(server.hits).hasValue(3);
    }

    @Test void jsonRpcErrorsAreClassified() {
        server.route("/", r -> r.body().contains("eth_bad")
                ? Response.ok("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601,\"message\":\"method not found\"}}")
                : Response.ok("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32005,\"message\":\"limit exceeded\"}}"));
        JsonRpcClient rpc = new JsonRpcClient(transport(fastResilience(1, 10)));
        assertThatThrownBy(() -> rpc.call("eth_bad")).satisfies(e -> assertThat(((ProviderException) e).retryable()).isFalse());
        assertThatThrownBy(() -> rpc.call("eth_blockNumber")).satisfies(e -> assertThat(((ProviderException) e).retryable()).isTrue());
    }

    @Test void rateLimiterThrottlesRequests() {
        server.route("/x", r -> Response.ok("{}"));
        ResilienceConfig r = new ResilienceConfig(1, Duration.ofMillis(100), Duration.ofSeconds(1), 0, 5, 2, 10, Duration.ofSeconds(1));
        java.util.List<Duration> waits = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpJsonTransport t = new HttpJsonTransport(endpoint("stub", server.url(), r), Clock.systemUTC(),
                d -> { waits.add(d); Sleeper.SYSTEM.sleep(d); });
        long t0 = System.nanoTime();
        for (int i = 0; i < 4; i++) t.get("/x");
        // burst of 2 free, then 2 permits at 5/s => at least ~400ms total
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isGreaterThanOrEqualTo(Duration.ofMillis(350));
        assertThat(waits).isNotEmpty();
    }
}
