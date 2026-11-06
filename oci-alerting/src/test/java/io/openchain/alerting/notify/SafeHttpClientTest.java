package io.openchain.alerting.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.openchain.alerting.notify.http.DnsResolver;
import io.openchain.alerting.notify.http.OutboundHttp.HttpResult;
import io.openchain.alerting.notify.http.SafeHttpClient;
import io.openchain.alerting.notify.http.SsrfException;
import io.openchain.alerting.notify.http.SsrfGuard;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSocketFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Exercises the real socket client against a local server (dev policy: http + loopback explicitly allowed). */
class SafeHttpClientTest {

    HttpServer server;
    String base;
    final AtomicReference<String> receivedBody = new AtomicReference<>();
    final AtomicReference<String> receivedSig = new AtomicReference<>();
    final AtomicInteger redirectTargetHits = new AtomicInteger();

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ok", ex -> {
            receivedBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedSig.set(ex.getRequestHeaders().getFirst("X-OCI-Signature"));
            byte[] r = "ok".getBytes();
            ex.sendResponseHeaders(200, r.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(r); }
        });
        server.createContext("/redirect", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.getResponseHeaders().add("Location", base + "/target");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/target", ex -> { redirectTargetHits.incrementAndGet(); ex.sendResponseHeaders(200, -1); ex.close(); });
        server.createContext("/huge", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(200, 0); // chunked
            try (OutputStream o = ex.getResponseBody()) { for (int i = 0; i < 1000; i++) o.write(new byte[1024]); } catch (IOException ignored) { }
        });
        server.createContext("/slow", ex -> {
            try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
            try { ex.sendResponseHeaders(200, -1); } catch (IOException ignored) { }
            ex.close();
        });
        server.createContext("/fail", ex -> { ex.getRequestBody().readAllBytes(); ex.sendResponseHeaders(503, -1); ex.close(); });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach void stop() { server.stop(0); }

    SafeHttpClient devClient(Duration readTimeout, int maxBytes) {
        SsrfGuard guard = new SsrfGuard(new SsrfGuard.Policy(true, true, Set.of()), DnsResolver.system());
        return new SafeHttpClient(guard, Duration.ofSeconds(2), readTimeout, Duration.ofSeconds(5), maxBytes,
                (SSLSocketFactory) SSLSocketFactory.getDefault());
    }

    @Test void postsBodyAndHeadersToPinnedAddress() throws Exception {
        HttpResult r = devClient(Duration.ofSeconds(2), 1024).post(URI.create(base + "/ok?x=1"),
                Map.of("X-OCI-Signature", "t=1,v1=ab", "Content-Type", "application/json"), "{\"a\":1}".getBytes());
        assertThat(r.status()).isEqualTo(200);
        assertThat(new String(r.body())).isEqualTo("ok");
        assertThat(receivedBody.get()).isEqualTo("{\"a\":1}");
        assertThat(receivedSig.get()).isEqualTo("t=1,v1=ab");
    }

    @Test void redirectsAreNeverFollowed() throws Exception {
        HttpResult r = devClient(Duration.ofSeconds(2), 1024).post(URI.create(base + "/redirect"), Map.of(), new byte[0]);
        assertThat(r.status()).isEqualTo(302);
        assertThat(r.redirect()).isTrue();
        assertThat(redirectTargetHits.get()).isZero();
    }

    @Test void responseBodyIsCapped() throws Exception {
        HttpResult r = devClient(Duration.ofSeconds(2), 4096).post(URI.create(base + "/huge"), Map.of(), new byte[0]);
        assertThat(r.truncated()).isTrue();
        assertThat(r.body().length).isLessThanOrEqualTo(4096);
    }

    @Test void readTimeoutIsEnforced() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> devClient(Duration.ofMillis(300), 1024).post(URI.create(base + "/slow"), Map.of(), new byte[0]))
                .isInstanceOf(SocketTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
    }

    @Test void transientStatusIsReportedRetryable() throws Exception {
        assertThat(devClient(Duration.ofSeconds(2), 1024).post(URI.create(base + "/fail"), Map.of(), new byte[0]).retryable()).isTrue();
    }

    @Test void headerInjectionIsRejected() {
        assertThatThrownBy(() -> devClient(Duration.ofSeconds(2), 1024).post(URI.create(base + "/ok"),
                Map.of("X-Test", "a\r\nX-Injected: 1"), new byte[0])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void strictPolicyBlocksLoopbackBeforeConnecting() {
        SafeHttpClient strict = new SafeHttpClient(new SsrfGuard(SsrfGuard.Policy.strict(), h -> List.of(InetAddress.getLoopbackAddress())));
        assertThatThrownBy(() -> strict.post(URI.create(base.replace("http", "https") + "/ok"), Map.of(), new byte[0]))
                .isInstanceOf(SsrfException.class);
        assertThatThrownBy(() -> strict.post(URI.create("https://hooks.attacker.test/ok"), Map.of(), new byte[0]))
                .isInstanceOf(SsrfException.class).hasMessageContaining("loopback");
        assertThat(receivedBody.get()).isNull();
    }
}
