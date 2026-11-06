package io.openchain.core.model;

import java.util.List;

/** Cursor-based page. {@code nextCursor} is null on the last page. */
public record Page<T>(List<T> items, String nextCursor) {
    public static <T> Page<T> of(List<T> items) { return new Page<>(List.copyOf(items), null); }
}
