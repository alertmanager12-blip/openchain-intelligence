package io.openchain.blockchain.transport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Small null-safe helpers over Jackson trees. */
public final class Json {
    public static final JsonMapper MAPPER = JsonMapper.shared();

    private Json() {}

    /** Text value or null when missing/null. */
    public static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        return node.isString() ? node.stringValue() : node.asString();
    }

    public static String text(JsonNode node, String field) { return node == null ? null : text(node.path(field)); }

    public static boolean present(JsonNode node) { return node != null && !node.isMissingNode() && !node.isNull(); }

    public static long longValue(JsonNode node, String field, long dflt) {
        JsonNode v = node.path(field);
        if (!present(v)) return dflt;
        return v.isNumber() ? v.longValue() : Long.parseLong(v.asString());
    }
}
