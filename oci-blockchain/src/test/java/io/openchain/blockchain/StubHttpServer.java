package io.openchain.blockchain;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Local HTTP stub (no network). Routes by path prefix; handler receives (path+query, body). */
public final class StubHttpServer implements AutoCloseable {
    public record Request(String method, String pathAndQuery, String body) {}
    public record Response(int status, String body, Map<String, String> headers) {
        public static Response ok(String body) { return new Response(200, body, Map.of()); }
        public static Response status(int s) { return new Response(s, "{}", Map.of()); }
    }

    private final HttpServer server;
    private final Map<String, Function<Request, Response>> routes = new ConcurrentHashMap<>();
    public final AtomicInteger hits = new AtomicInteger();

    public StubHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            URI uri = ex.getRequestURI();
            String pq = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            Function<Request, Response> h = routes.entrySet().stream()
                    .filter(e -> uri.getRawPath().equals(e.getKey()) || (e.getKey().endsWith("*") && uri.getRawPath().startsWith(e.getKey().substring(0, e.getKey().length() - 1))))
                    .map(Map.Entry::getValue).findFirst().orElse(r -> Response.status(404));
            Response r;
            try {
                r = h.apply(new Request(ex.getRequestMethod(), pq, body));
            } catch (RuntimeException e) {
                r = new Response(500, "{\"error\":\"" + e.getMessage() + "\"}", Map.of());
            }
            byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
            r.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(r.status(), out.length == 0 ? -1 : out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    public StubHttpServer route(String path, Function<Request, Response> handler) { routes.put(path, handler); return this; }

    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    @Override public void close() { server.stop(0); }
}
