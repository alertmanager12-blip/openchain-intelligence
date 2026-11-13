package io.openchain.intelligence.memory;

import io.openchain.core.model.Address;

/**
 * Compact "was this address ever seen?" memory (Bloom filter), independent of the bounded working set.
 *
 * <p>Why: a wallet profile evicted by the working-set caps makes an old wallet look brand new. With this filter an
 * address without a profile is either never seen (genuinely new: its age is 0, a fact) or seen before (its age is
 * unknown). Answers: {@code false} is always exact (never seen); {@code true} may be wrong with a small probability
 * that grows with the fill ratio (then a new address is reported as "seen": its age is withheld, never invented).
 *
 * <p>Fixed memory: {@code bits / 8} bytes, whatever the number of addresses. Not persisted: it restarts empty, like
 * the observation warm-up. Not thread-safe: callers serialise access (the ingestion lock).
 */
public final class SeenAddresses {
    private static final int HASHES = 7;
    private final long[] words;
    private final long bits;
    private long setBits, inserted;

    /** @param bits size of the filter, rounded up to a multiple of 64 (at least 64); about 9.6 bits per address keep errors near 1 % */
    public SeenAddresses(long bits) {
        long n = Math.max(64, bits);
        int w = (int) Math.min(Integer.MAX_VALUE - 8, (n + 63) / 64);
        this.words = new long[w];
        this.bits = (long) w * 64;
    }

    /** Records the address; returns true when it was (probably) already there. */
    public boolean add(Address a) {
        long h1 = h1(a), h2 = h2(a);
        boolean present = true;
        for (int i = 0; i < HASHES; i++) {
            long bit = Long.remainderUnsigned(h1 + i * h2, bits);
            int w = (int) (bit >>> 6);
            long mask = 1L << (bit & 63);
            if ((words[w] & mask) == 0) { words[w] |= mask; setBits++; present = false; }
        }
        if (!present) inserted++;
        return present;
    }

    /** False: never seen (exact). True: seen before, or (rarely) a collision. */
    public boolean mightContain(Address a) {
        long h1 = h1(a), h2 = h2(a);
        for (int i = 0; i < HASHES; i++) {
            long bit = Long.remainderUnsigned(h1 + i * h2, bits);
            if ((words[(int) (bit >>> 6)] & (1L << (bit & 63))) == 0) return false;
        }
        return true;
    }

    /** Distinct addresses recorded (collisions excluded, so slightly under the real count when the filter fills up). */
    public long inserted() { return inserted; }

    /** Share of bits set, 0..1. Above about 0.5 the error rate exceeds 1 %: raise the size. */
    public double fillRatio() { return (double) setBits / bits; }

    /** Probability that a never-seen address is reported as seen, from the current fill ratio. */
    public double falsePositiveRate() { return Math.pow(fillRatio(), HASHES); }

    public long sizeBytes() { return bits / 8; }

    private static long h1(Address a) {
        long h = 0xcbf29ce484222325L ^ a.network().ordinal(); // FNV-1a over the characters
        String v = a.value();
        for (int i = 0; i < v.length(); i++) { h ^= v.charAt(i); h *= 0x100000001b3L; }
        return mix(h);
    }

    private static long h2(Address a) {
        long h = 1125899906842597L + a.network().ordinal();
        String v = a.value();
        for (int i = 0; i < v.length(); i++) h = 31 * h + v.charAt(i);
        return mix(h) | 1L; // odd: the probe sequence covers the table
    }

    private static long mix(long z) { // splitmix64 finaliser
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
