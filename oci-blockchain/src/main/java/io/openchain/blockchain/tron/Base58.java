package io.openchain.blockchain.tron;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Bitcoin-alphabet Base58 and Base58Check (double SHA-256, 4-byte checksum). */
public final class Base58 {
    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final BigInteger BASE = BigInteger.valueOf(58);

    private Base58() {}

    public static String encode(byte[] input) {
        BigInteger n = new BigInteger(1, input);
        StringBuilder sb = new StringBuilder();
        while (n.signum() > 0) {
            BigInteger[] qr = n.divideAndRemainder(BASE);
            sb.append(ALPHABET.charAt(qr[1].intValue()));
            n = qr[0];
        }
        for (int i = 0; i < input.length && input[i] == 0; i++) sb.append('1');
        return sb.reverse().toString();
    }

    public static byte[] decode(String input) {
        BigInteger n = BigInteger.ZERO;
        for (char c : input.toCharArray()) {
            int d = ALPHABET.indexOf(c);
            if (d < 0) throw new IllegalArgumentException("invalid base58 character");
            n = n.multiply(BASE).add(BigInteger.valueOf(d));
        }
        byte[] raw = n.toByteArray();
        if (raw.length > 1 && raw[0] == 0) raw = Arrays.copyOfRange(raw, 1, raw.length); // sign byte
        if (n.signum() == 0) raw = new byte[0];
        int zeros = 0;
        while (zeros < input.length() && input.charAt(zeros) == '1') zeros++;
        byte[] out = new byte[zeros + raw.length];
        System.arraycopy(raw, 0, out, zeros, raw.length);
        return out;
    }

    public static String encodeCheck(byte[] payload) {
        byte[] checksum = Arrays.copyOf(doubleSha256(payload), 4);
        byte[] all = Arrays.copyOf(payload, payload.length + 4);
        System.arraycopy(checksum, 0, all, payload.length, 4);
        return encode(all);
    }

    public static byte[] decodeCheck(String input) {
        byte[] all = decode(input);
        if (all.length < 5) throw new IllegalArgumentException("base58check payload too short");
        byte[] payload = Arrays.copyOf(all, all.length - 4);
        byte[] expected = Arrays.copyOf(doubleSha256(payload), 4);
        if (!MessageDigest.isEqual(expected, Arrays.copyOfRange(all, all.length - 4, all.length))) {
            throw new IllegalArgumentException("invalid base58check checksum");
        }
        return payload;
    }

    private static byte[] doubleSha256(byte[] b) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(md.digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
