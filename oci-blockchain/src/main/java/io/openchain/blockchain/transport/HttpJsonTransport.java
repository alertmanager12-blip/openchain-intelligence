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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Resilient JSON-over-HTTP transport on top of {@link java.net.http.HttpClient}, for one endpoint or an ordered list of
 * equivalent endpoints (failover).
 * <p>Pipeline per logical call and endpoint: retry(backoff+jitter) -> circuit breaker -> rate limiter -> HTTP attempt.
 * Error classification: timeout / IO / 429 / 5xx = retryable; other 4xx and malformed JSON = permanent.
 * <p>Failover: when a call still fails with a <b>retryable</b> error on an endpoint (retries exhausted, or its circuit is
 * open), the next endpoint is tried; the endpoint that last succeeded is preferred for the following calls. A permanent
 * error is returned as is (another node would answer the same). Each endpoint has its own circuit breaker, rate limiter
 * and headers, so a provider API key configured for one endpoint is never sent to another.
 */
public final class HttpJsonTransport {
    private final List<Lane> lanes;
    private volatile int preferred;

    public HttpJsonTransport(RpcEndpoint endpoint) { this(endpoint, Clock.systemUTC(), Sleeper.SYSTEM); }

    public HttpJsonTransport(RpcEndpoint endpoint, Clock clock, Sleeper sleeper) { this(List.of(endpoint), clock, sleeper); }

    /** @param endpoints primary first, then fallbacks, tried in order */
    public HttpJsonTransport(List<RpcEndpoint> endpoints, Clock clock, Sleeper sleeper) {
        if (endpoints == null || endpoints.isEmpty()) throw new IllegalArgumentException("at least one endpoint is required");
        List<Lane> l = new ArrayList<>();
        for (RpcEndpoint e : endpoints) l.add(new Lane(e, clock, sleeper));
        this.lanes = List.copyOf(l);
    }

    /** The endpoint currently preferred (the primary unless a failover happened). */
    public RpcEndpoint endpoint() { return lanes.get(preferred).endpoint; }

    public CircuitBreaker.State circuitState() { return lanes.get(preferred).breaker.state(); }

    /** Number of configured endpoints (1 = no failover). */
    public int endpointCount() { return lanes.size(); }

    public JsonNode post(String path, JsonNode body) {
        String json = Json.MAPPER.writeValueAsString(body);
        return call(lane -> lane.retry.execute(() -> lane.breaker.execute(() -> lane.attempt(path,
                HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8), "POST"))));
    }

    public JsonNode get(String path) {
        return call(lane -> lane.retry.execute(() -> lane.breaker.execute(() -> lane.attempt(path,
                HttpRequest.BodyPublishers.noBody(), "GET"))));
    }

    private JsonNode call(Function<Lane, JsonNode> op) {
        int start = preferred;
        ProviderException last = null;
        for (int i = 0; i < lanes.size(); i++) {
            int idx = (start + i) % lanes.size();
            try {
                JsonNode r = op.apply(lanes.get(idx));
                preferred = idx;
                return r;
            } catch (ProviderException e) {
                if (!e.retryable() || lanes.size() == 1) throw e;
                last = e;
            }
        }
        throw last;
    }

    /** One endpoint with its own HTTP client, retry policy, circuit breaker and rate limiter. */
    private static final class Lane {
        final RpcEndpoint endpoint;
        final HttpClient client;
        final RetryPolicy retry;
        final CircuitBreaker breaker;
        final TokenBucketRateLimiter limiter; // nullable

        Lane(RpcEndpoint endpoint, Clock clock, Sleeper sleeper) {
            this.endpoint = endpoint;
            ResilienceConfig r = endpoint.resilience();
            this.client = HttpClient.newBuilder().connectTimeout(endpoint.connectTimeout())
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            this.retry = new RetryPolicy(r, sleeper);
            this.breaker = new CircuitBreaker(endpoint.name(), r.failureThreshold(), r.openDuration(), clock);
            this.limiter = r.permitsPerSecond() > 0
                    ? new TokenBucketRateLimiter(r.permitsPerSecond(), r.burst(), System::nanoTime, sleeper) : null;
        }

        JsonNode attempt(String path, HttpRequest.BodyPublisher body, String method) {
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
            if (status >= 400) {
                // some JSON-RPC gateways answer a JSON-RPC error with HTTP 400 (e.g. dRPC "ranges over 10000 blocks are not
                // supported"): hand that error to JsonRpcClient so that it is classified (range too large, unsupported method...)
                JsonNode rpcError = "POST".equals(method) ? jsonRpcError(resp.body()) : null;
                if (rpcError != null) return rpcError;
                throw new ProviderException(endpoint.name() + ": HTTP " + status, false);
            }
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
    }

    /** The body when it is a JSON-RPC 2.0 error response, else null. */
    private static JsonNode jsonRpcError(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode n = Json.MAPPER.readTree(body);
            return n != null && n.isObject() && n.path("error").isObject() && n.has("jsonrpc") ? n : null;
        } catch (JacksonException e) {
            return null;
        }
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
