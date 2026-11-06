package io.openchain.app.persistence;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON codec for free-form maps (alert evidence, notification snapshots) that keeps the Java value types that plain
 * JSON loses: {@link BigDecimal} (amounts), {@link BigInteger}, {@link Instant} and {@link Duration} are written as
 * {@code {"@t": "bd|bi|ts|dur", "v": "<string>"}} and restored on read, so an alert or a retried notification is
 * identical before and after a restart (API rendering, webhook payload bytes). Other non-JSON values are stored
 * with {@code toString()}. Strings, booleans, integral numbers, doubles, lists and maps are stored as is.
 */
public final class TypedJson {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TAG = "@t";

    private TypedJson() {}

    public static String write(Object value) { return JSON.writeValueAsString(encode(value)); }

    public static Map<String, Object> readMap(String json) {
        if (json == null) return new LinkedHashMap<>();
        Object v = decode(JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {}));
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) v;
        return m;
    }

    public static List<String> readStrings(String json) {
        if (json == null) return new ArrayList<>();
        return JSON.readValue(json, new TypeReference<ArrayList<String>>() {});
    }

    static Object encode(Object v) {
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof Integer || v instanceof Long
                || v instanceof Double || v instanceof Short || v instanceof Float) return v;
        if (v instanceof BigDecimal bd) return tagged("bd", bd.toString());
        if (v instanceof BigInteger bi) return tagged("bi", bi.toString());
        if (v instanceof Instant i) return tagged("ts", i.toString());
        if (v instanceof Duration d) return tagged("dur", d.toString());
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), encode(x)));
            return out;
        }
        if (v instanceof Iterable<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(x -> out.add(encode(x)));
            return out;
        }
        if (v instanceof Enum<?> e) return e.name();
        if (v instanceof Number n) return tagged("bd", new BigDecimal(n.toString()).toString());
        return v.toString();
    }

    static Object decode(Object v) {
        if (v instanceof Map<?, ?> m) {
            if (m.size() == 2 && m.get(TAG) instanceof String t && m.get("v") instanceof String s) {
                switch (t) {
                    case "bd": return new BigDecimal(s);
                    case "bi": return new BigInteger(s);
                    case "ts": return Instant.parse(s);
                    case "dur": return Duration.parse(s);
                    default: break;
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), decode(x)));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            l.forEach(x -> out.add(decode(x)));
            return out;
        }
        return v;
    }

    private static Map<String, Object> tagged(String type, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(TAG, type);
        m.put("v", value);
        return m;
    }
}
