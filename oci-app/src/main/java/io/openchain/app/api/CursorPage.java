package io.openchain.app.api;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Cursor page {@code {items, nextCursor, truncated}} (mirrors io.openchain.core.model.Page). Over in-memory lists
 * the opaque cursor encodes an offset. TODO(persistence): keyset cursor on (ts, id) with 24 h expiry.
 */
public record CursorPage<T>(List<T> items, String nextCursor, boolean truncated) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    public static <T> CursorPage<T> of(List<T> all, String cursor, Integer limit) {
        int n = limit(limit);
        int offset = decode(cursor);
        int from = Math.min(offset, all.size());
        int to = Math.min(all.size(), from + n);
        return new CursorPage<>(List.copyOf(all.subList(from, to)), to < all.size() ? encode(to) : null, false);
    }

    public static int limit(Integer limit) {
        if (limit == null) return DEFAULT_LIMIT;
        if (limit < 1 || limit > MAX_LIMIT) throw ApiException.badRequest("limit must be between 1 and " + MAX_LIMIT);
        return limit;
    }

    public static String encode(int offset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("o:" + offset).getBytes(StandardCharsets.UTF_8));
    }

    public static int decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            String s = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!s.startsWith("o:")) throw new IllegalArgumentException();
            int o = Integer.parseInt(s.substring(2));
            if (o < 0) throw new IllegalArgumentException();
            return o;
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid cursor");
        }
    }
}
