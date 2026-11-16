package io.openchain.intelligence.profile;

import java.time.Instant;

/**
 * Dedup keys still remembered by profiles after their flow left the flow store (step 3 diagnostic; see
 * docs/KEY_COMPACTION_IMPLEMENTATION_REPORT.md). Sizes are ESTIMATES: a map entry plus a Latin-1 string of the key's
 * length (compressed oops); a string also held elsewhere (the other party's profile, the balance book) is counted for
 * each holder, so the byte figures are upper bounds and {@code distinctOrphanFlowIdsEstimate} (sampled) corrects for it.
 *
 * @param orphanFlowKeysByProfile     profiles per bucket of orphan count: 0, 1, 2-4, 5-16, 17-256, 257-4096, more
 * @param orphanFlowKeysByIdleSeconds orphan keys per bucket of profile inactivity (now minus last activity): up to 1 h,
 *                                    6 h, 24 h, 3 d, 7 d, more. A key is at least as old as its profile's inactivity.
 */
public record OrphanKeyReport(Instant at, long profiles, long eventKeys, long txLevelKeys, long liveFlowKeys,
                              long orphanFlowKeys, long orphanFlowKeyBytesUpperBound, long distinctOrphanFlowIdsEstimate,
                              long txHashKeys, long orphanTxHashKeys, long orphanTxHashKeyBytesUpperBound,
                              long profilesWithOrphans, long[] orphanFlowKeysByProfile, long[] orphanFlowKeysByIdleSeconds,
                              long millis) {
    static final long[] PER_PROFILE_BUCKETS = {0, 1, 4, 16, 256, 4_096};
    static final long[] IDLE_BUCKETS = {3_600, 6 * 3_600, 24 * 3_600, 3 * 86_400, 7 * 86_400};

    static int bucket(long[] upperBounds, long v) {
        for (int i = 0; i < upperBounds.length; i++) if (v <= upperBounds[i]) return i;
        return upperBounds.length;
    }

    /** Entry (LinkedHashMap.Entry 40 B + table slot ~8 B) + String (24 B) + byte[] (16 B header + length, 8-aligned). */
    static long entryBytes(String k) { return 48 + 24 + ((16L + k.length() + 7) / 8) * 8; }
}
