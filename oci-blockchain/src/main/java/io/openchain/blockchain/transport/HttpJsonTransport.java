package io.openchain.blockchain.transport;

import io.openchain.core.provider.ProviderException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Resilient JSON-over-HTTP transport for ONE endpoint, on top of {@link java.net.http.HttpClient}.
 * Pipeline per logical call: retry(backoff+jitter) -> circuit breaker -> rate limiter -> HTTP attempt.
 * Error classification: timeout / IO / 429 / 5xx = retryable; other 4xx and malformed JSON = permanent.
 */
public final class HttpJsonTransport {
    private final RpcEndpoint endpoint;
    private final HttpClient client;
    private final RetryPolicy retry;
    private final CircuitBreaker breaker;
    private final TokenBucketRateLimiter limiter; // nullable

    public HttpJsonTransport(RpcEndpoint endpoint) { this(endpoint, Clock.systemUTC(), Sleeper.SYSTEM); }

    public HttpJsonTransport(RpcEndpoint endpoint, Clock clock, Sleeper sleeper) {
        this.endpoint = endpoint;
        ResilienceConfig r = endpoint.resilience();
        this.client = HttpClient.newBuilder().connectTimeout(endpoint.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.retry = new RetryPolicy(r, sleeper);
        this.breaker = new CircuitBreaker(endpoint.name(), r.failureThreshold(), r.openDuration(), clock);
        this.limiter = r.permitsPerSecond() > 0
                ? new TokenBucketRateLimiter(r.permitsPerSecond(), r.burst(), System::nanoTime, sleeper) : null;
    }

    public RpcEndpoint endpoint() { return endpoint; }
    public CircuitBreaker.State circuitState() { return breaker.state(); }

    public JsonNode post(String path, JsonNode body) {
        String json = Json.MAPPER.writeValueAsString(body);
        return retry.execute(() -> breaker.execute(() -> attempt(path,
                HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8), "POST")));
    }

    public JsonNode get(String path) {
        return retry.execute(() -> breaker.execute(() -> attempt(path, HttpRequest.BodyPublishers.noBody(), "GET")));
    }

    private JsonNode attempt(String path, HttpRequest.BodyPublisher body, String method) {
        if (limiter != null) limiter.acquire();
        HttpRequest.Builder b = HttpRequest.newBuilder(resolve(path)).timeout(endpoint.requestTimeout())
                .header("Accept", "application/json").method(method, body);
        if ("POST".equals(method)) b.header("Content-Type", "application/json");
        endpoint.headers().forEach(b::header);
        HttpResponse<String> resp;
        try {
            resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new ProviderException(endpoint.name() + ": timeout", true, e);
        } catch (IOException e) {
            throw new ProviderException(endpoint.name() + ": I/O error " + e.getClass().getSimpleName(), true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(endpoint.name() + ": interrupted", false, e);
        }
        int status = resp.statusCode();
        if (status == 429) {
            throw new RetryPolicy.RetryAfterException(endpoint.name() + ": HTTP 429 rate limited", retryAfter(resp));
        }
        if (status >= 500) throw new ProviderException(endpoint.name() + ": HTTP " + status, true);
        if (status >= 400) throw new ProviderException(endpoint.name() + ": HTTP " + status, false);
        try {
            return Json.MAPPER.readTree(resp.body() == null || resp.body().isBlank() ? "null" : resp.body());
        } catch (JacksonException e) {
            throw new ProviderException(endpoint.name() + ": malformed JSON response", false, e);
        }
    }

    private URI resolve(String path) {
        String base = endpoint.baseUri().toString();
        if (path == null || path.isEmpty()) return endpoint.baseUri();
        return URI.create(base.endsWith("/") && path.startsWith("/") ? base + path.substring(1)
                : !base.endsWith("/") && !path.startsWith("/") ? base + "/" + path : base + path);
    }

    private static Duration retryAfter(HttpResponse<?> resp) {
        Optional<String> h = resp.headers().firstValue("Retry-After");
        try {
            return h.map(v -> Duration.ofSeconds(Long.parseLong(v.trim()))).orElse(null);
        } catch (NumberFormatException e) {
            return null; // HTTP-date form: fall back to computed backoff
        }
    }
}
