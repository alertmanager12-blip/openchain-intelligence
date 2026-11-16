package io.openchain.intelligence.memory;

import java.time.Instant;

/** Cut-off instant that brings a set of timestamps under a cap (ADR-0024 step 3b). */
public final class Caps {
    private Caps() {}

    /**
     * @param sortedMillis timestamps in ascending order (epoch milliseconds)
     * @return the instant before which items must be removed so that at most {@code max} remain, or null when none must go.
     *         Items sharing the boundary instant are removed with it, so the cap always holds (it may keep fewer).
     */
    public static Instant cutoff(long[] sortedMillis, int max) {
        int n = sortedMillis.length;
        if (n <= max) return null;
        if (max <= 0) return Instant.ofEpochMilli(sortedMillis[n - 1] + 1);
        long boundary = sortedMillis[n - max];
        // the boundary instant is shared with an older item that must go: remove the whole instant
        return Instant.ofEpochMilli(sortedMillis[n - max - 1] == boundary ? boundary + 1 : boundary);
    }
}
