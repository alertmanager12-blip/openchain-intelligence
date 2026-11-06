package io.openchain.blockchain.evm;

import java.nio.charset.StandardCharsets;

/**
 * Test-only Keccak-256 (the original Keccak submission used by Ethereum, padding byte 0x01 - NOT NIST SHA3-256,
 * which pads with 0x06). Straight implementation of Keccak-f[1600] (24 rounds, rate 1088 bits) from the Keccak team
 * reference specification (https://keccak.team/keccak_specs_summary.html). No dependency: used to verify the event
 * topic constants hard-coded in production code, never shipped.
 */
final class Keccak256 {
    private static final long[] RC = {
            0x0000000000000001L, 0x0000000000008082L, 0x800000000000808aL, 0x8000000080008000L,
            0x000000000000808bL, 0x0000000080000001L, 0x8000000080008081L, 0x8000000000008009L,
            0x000000000000008aL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000aL,
            0x000000008000808bL, 0x800000000000008bL, 0x8000000000008089L, 0x8000000000008003L,
            0x8000000000008002L, 0x8000000000000080L, 0x000000000000800aL, 0x800000008000000aL,
            0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L};
    /** Rotation offsets r[x][y], indexed x + 5y. */
    private static final int[] R = {
            0, 1, 62, 28, 27,
            36, 44, 6, 55, 20,
            3, 10, 43, 25, 39,
            41, 45, 15, 21, 8,
            18, 2, 61, 56, 14};
    private static final int RATE = 136;

    private Keccak256() {}

    static byte[] hash(byte[] in) {
        long[] a = new long[25];
        int blocks = in.length / RATE + 1;
        byte[] padded = new byte[blocks * RATE];
        System.arraycopy(in, 0, padded, 0, in.length);
        padded[in.length] ^= 0x01;
        padded[padded.length - 1] ^= (byte) 0x80;
        for (int b = 0; b < blocks; b++) {
            for (int i = 0; i < RATE / 8; i++) {
                long lane = 0;
                for (int k = 7; k >= 0; k--) lane = (lane << 8) | (padded[b * RATE + 8 * i + k] & 0xFFL);
                a[i] ^= lane;
            }
            permute(a);
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 4; i++) for (int k = 0; k < 8; k++) out[8 * i + k] = (byte) (a[i] >>> (8 * k));
        return out;
    }

    static String hex(String text) { return "0x" + EvmCodec.encodeHex(hash(text.getBytes(StandardCharsets.UTF_8))); }

    private static void permute(long[] a) {
        long[] c = new long[5], b = new long[25];
        for (int round = 0; round < 24; round++) {
            for (int x = 0; x < 5; x++) c[x] = a[x] ^ a[x + 5] ^ a[x + 10] ^ a[x + 15] ^ a[x + 20];
            for (int x = 0; x < 5; x++) {
                long d = c[(x + 4) % 5] ^ Long.rotateLeft(c[(x + 1) % 5], 1);
                for (int y = 0; y < 25; y += 5) a[x + y] ^= d;
            }
            for (int x = 0; x < 5; x++)
                for (int y = 0; y < 5; y++)
                    b[y + 5 * ((2 * x + 3 * y) % 5)] = Long.rotateLeft(a[x + 5 * y], R[x + 5 * y]);
            for (int x = 0; x < 5; x++)
                for (int y = 0; y < 5; y++)
                    a[x + 5 * y] = b[x + 5 * y] ^ (~b[(x + 1) % 5 + 5 * y] & b[(x + 2) % 5 + 5 * y]);
            a[0] ^= RC[round];
        }
    }
}
