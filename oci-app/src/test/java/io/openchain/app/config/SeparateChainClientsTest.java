package io.openchain.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.openchain.blockchain.transport.CircuitBreaker;
import io.openchain.blockchain.transport.HttpJsonTransport;
import io.openchain.blockchain.transport.ResilienceConfig;
import io.openchain.core.model.Network;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Production 2026-11-16: address histories made TronGrid refuse requests, opened the circuit breaker shared with live
 * indexing, and TRON stopped for about 80 minutes. Live indexing and background work now use two clients: a breaker
 * opened by background work leaves live indexing running, and together they ask no more of the provider than before.
 */
class SeparateChainClientsTest {
    HttpServer server;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            boolean history = ex.getRequestURI().getPath().startsWith("/v1/accounts");
            byte[] body = (history ? "{}" : "{\"ok\":true}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(history ? 503 : 200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void aBreakerOpenedByBackgroundWorkLeavesLiveIndexingRunning() {
        OciProperties.Chain c = new OciProperties.Chain();
        c.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        HttpJsonTransport live = Bootstrap.transport(Network.TRON, c, "", Bootstrap.LIVE_RESILIENCE);
        HttpJsonTransport background = Bootstrap.transport(Network.TRON, c, "-background", Bootstrap.BACKGROUND_RESILIENCE);

        for (int i = 0; i < 3 && background.circuitState() != CircuitBreaker.State.OPEN; i++) {
            assertThatThrownBy(() -> background.get("/v1/accounts/TX/transactions")).isNotNull();
        }
        assertThat(background.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(live.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(live.get("/wallet/getnowblock").path("ok").asBoolean()).isTrue();
        assertThat(background.endpoint().name()).isEqualTo("tron-background-primary");
        assertThat(live.endpoint().name()).isEqualTo("tron-primary");
    }

    @Test void togetherTheClientsAskNoMoreThanTheSingleClientDid() {
        double before = ResilienceConfig.defaults().permitsPerSecond();
        assertThat(Bootstrap.LIVE_RESILIENCE.permitsPerSecond() + Bootstrap.BACKGROUND_RESILIENCE.permitsPerSecond()).isEqualTo(before);
        assertThat(Bootstrap.LIVE_RESILIENCE.permitsPerSecond()).isGreaterThan(Bootstrap.BACKGROUND_RESILIENCE.permitsPerSecond());
    }
}
