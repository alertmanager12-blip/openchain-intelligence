package io.openchain.blockchain.transport;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Vendor-neutral endpoint definition, built from configuration. Secrets (API keys) travel in {@code headers}
 * and are never logged nor included in exception messages.
 */
public record RpcEndpoint(String name, URI baseUri, Map<String, String> headers, Duration connectTimeout,
                          Duration requestTimeout, ResilienceConfig resilience) {
    public RpcEndpoint {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(baseUri, "baseUri");
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(15) : requestTimeout;
        resilience = resilience == null ? ResilienceConfig.defaults() : resilience;
    }

    public static RpcEndpoint of(String name, String url) {
        return new RpcEndpoint(name, URI.create(url), Map.of(), null, null, null);
    }

    public RpcEndpoint withRequestTimeout(Duration t) {
        return new RpcEndpoint(name, baseUri, headers, connectTimeout, t, resilience);
    }

    @Override public String toString() { return "RpcEndpoint[" + name + "]"; }
}
