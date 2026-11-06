package io.openchain.blockchain.transport;

import io.openchain.core.provider.ProviderException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** JSON-RPC 2.0 client over {@link HttpJsonTransport}. */
public final class JsonRpcClient {
    /** JSON-RPC error codes commonly used by nodes for transient conditions (limit exceeded, resource unavailable). */
    private static final List<Integer> RETRYABLE_CODES = List.of(-32005, -32002, 429);

    private final HttpJsonTransport transport;
    private final AtomicLong ids = new AtomicLong();

    public JsonRpcClient(HttpJsonTransport transport) { this.transport = transport; }

    public HttpJsonTransport transport() { return transport; }

    /** Returns the {@code result} node (may be a NullNode). Params: String, Boolean, Number, JsonNode. */
    public JsonNode call(String method, Object... params) {
        ObjectNode req = Json.MAPPER.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", ids.incrementAndGet());
        req.put("method", method);
        ArrayNode arr = req.putArray("params");
        for (Object p : params) {
            switch (p) {
                case String s -> arr.add(s);
                case Boolean b -> arr.add(b);
                case Long l -> arr.add(l);
                case Integer i -> arr.add(i);
                case JsonNode n -> arr.add(n);
                default -> throw new IllegalArgumentException("unsupported param type " + p.getClass());
            }
        }
        JsonNode resp = transport.post("", req);
        JsonNode err = resp == null ? null : resp.path("error");
        if (Json.present(err)) {
            int code = err.path("code").asInt(0);
            String msg = Json.text(err, "message");
            throw new JsonRpcException(transport.endpoint().name() + ": " + method + " RPC error " + code
                    + (msg == null ? "" : " " + truncate(msg)), method, code, msg == null ? null : truncate(msg),
                    RETRYABLE_CODES.contains(code));
        }
        if (resp == null || !resp.has("result")) {
            throw new ProviderException(transport.endpoint().name() + ": " + method + " returned no result", true);
        }
        return resp.get("result");
    }

    private static String truncate(String s) { return s.length() > 200 ? s.substring(0, 200) : s; }
}
