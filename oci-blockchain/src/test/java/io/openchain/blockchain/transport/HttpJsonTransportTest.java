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

    @Test void jsonRpcErrorSentWithHttp400IsClassifiedByTheJsonRpcClient() {
        // dRPC free plan (observed 2026-11-07): {"error":{"message":"ranges over 10000 blocks are not supported...","code":35}} + HTTP 400
        server.route("/", r -> new Response(400, "{\"id\":1,\"jsonrpc\":\"2.0\",\"error\":{\"message\":\"ranges over 10000 blocks "
                + "are not supported on free plan\",\"code\":35}}", Map.of()));
        JsonRpcClient rpc = new JsonRpcClient(transport(fastResilience(4, 10)));
        assertThatThrownBy(() -> rpc.call("eth_getLogs", Json.MAPPER.createObjectNode()))
                .isInstanceOf(JsonRpcException.class).satisfies(e -> assertThat(((JsonRpcException) e).rangeTooLarge()).isTrue());
        assertThat(server.hits).hasValue(1);
        // a plain 400 on GET (TronGrid style) is still a non-retryable provider error
        server.route("/x", r -> new Response(400, "{\"error\":{\"code\":1}}", Map.of()));
        assertThatThrownBy(() -> transport(fastResilience(4, 10)).get("/x")).isInstanceOf(ProviderException.class)
                .isNotInstanceOf(JsonRpcException.class);
    }

    @Test void failsOverToTheNextEndpointOnRetryableErrorsAndKeepsPreferringIt() throws Exception {
        try (StubHttpServer backup = new StubHttpServer()) {
            server.route("/x", r -> Response.status(503));
            backup.route("/x", r -> Response.ok("{\"from\":\"backup\"}"));
            HttpJsonTransport t = new HttpJsonTransport(java.util.List.of(endpoint("primary", server.url(), fastResilience(2, 10)),
                    endpoint("backup", backup.url(), fastResilience(2, 10))), Clock.systemUTC(), sleeper);
            assertThat(t.get("/x").path("from").asString()).isEqualTo("backup");
            assertThat(server.hits).hasValue(2); // retries exhausted on the primary first
            assertThat(t.endpoint().name()).isEqualTo("backup");
            t.get("/x");
            assertThat(server.hits).hasValue(2); // the healthy endpoint is now preferred
            assertThat(backup.hits).hasValue(2);
        }
    }

    @Test void permanentErrorsDoNotFailOver() throws Exception {
        try (StubHttpServer backup = new StubHttpServer()) {
            server.route("/x", r -> Response.status(404));
            backup.route("/x", r -> Response.ok("{}"));
            HttpJsonTransport t = new HttpJsonTransport(java.util.List.of(endpoint("primary", server.url(), fastResilience(2, 10)),
                    endpoint("backup", backup.url(), fastResilience(2, 10))), Clock.systemUTC(), sleeper);
            assertThatThrownBy(() -> t.get("/x")).isInstanceOf(ProviderException.class).hasMessageContaining("HTTP 404");
            assertThat(backup.hits).hasValue(0);
        }
    }

    @Test void historyLimitsOfNonArchiveNodesAreRecognised() {
        // PublicNode (observed 2026-11-07), pruned nodes, geth state; a range or rate-limit error is not a history limit
        assertThat(rpcError(-32602, "Archive requests require a personal token. Get one at: https://www.allnodes.com/publicnode").historyUnavailable()).isTrue();
        assertThat(rpcError(4444, "pruned history unavailable: requested 10, earliest available 15500000").historyUnavailable()).isTrue();
        assertThat(rpcError(-32000, "missing trie node abc (path ) state 0x12 is not available").historyUnavailable()).isTrue();
        assertThat(rpcError(-32005, "query returned more than 10000 results").historyUnavailable()).isFalse();
        assertThat(rpcError(-32005, "rate limit exceeded").historyUnavailable()).isFalse();
    }

    static JsonRpcException rpcError(int code, String message) {
        return new JsonRpcException("node: " + message, "eth_getLogs", code, message, false);
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
        // Warm up the local server and the HTTP stack with a throwaway transport: otherwise a slow first request
        // (JIT, server start) lets the bucket refill and the measured transport never has to wait (flaky test).
        new HttpJsonTransport(endpoint("warmup", server.url(), r), Clock.systemUTC(), Sleeper.SYSTEM).get("/x");
        HttpJsonTransport t = new HttpJsonTransport(endpoint("stub", server.url(), r), Clock.systemUTC(),
                d -> { waits.add(d); Sleeper.SYSTEM.sleep(d); });
        long t0 = System.nanoTime();
        for (int i = 0; i < 4; i++) t.get("/x");
        // burst of 2 free, then 2 permits at 5/s => at least ~400ms total
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isGreaterThanOrEqualTo(Duration.ofMillis(350));
        assertThat(waits).isNotEmpty();
    }
}
