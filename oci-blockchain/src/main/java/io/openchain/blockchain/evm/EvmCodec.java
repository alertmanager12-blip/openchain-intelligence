package io.openchain.blockchain.evm;

import java.math.BigInteger;
import java.util.Locale;

/** Hex/ABI helpers shared by EVM and TRON (TVM is EVM-compatible at the log level). */
public final class EvmCodec {
    /** keccak256("Transfer(address,address,uint256)"). */
    public static final String TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    /** balanceOf(address) selector. */
    public static final String BALANCE_OF = "0x70a08231";

    private EvmCodec() {}

    public static String strip0x(String hex) {
        return hex != null && (hex.startsWith("0x") || hex.startsWith("0X")) ? hex.substring(2) : hex;
    }

    public static String with0x(String hex) { return hex.startsWith("0x") ? hex.toLowerCase(Locale.ROOT) : "0x" + hex.toLowerCase(Locale.ROOT); }

    public static BigInteger quantity(String hex) {
        String h = strip0x(hex);
        if (h == null || h.isEmpty()) return BigInteger.ZERO;
        return new BigInteger(h, 16);
    }

    public static long quantityLong(String hex) { return quantity(hex).longValueExact(); }

    public static String toQuantity(long n) {
        if (n < 0) throw new IllegalArgumentException("negative quantity");
        return "0x" + Long.toHexString(n);
    }

    /** 32-byte topic -> 20-byte address hex (lowercase, no prefix). */
    public static String topicToAddressHex(String topic) {
        String h = strip0x(topic);
        if (h.length() != 64) throw new IllegalArgumentException("topic must be 32 bytes");
        return h.substring(24).toLowerCase(Locale.ROOT);
    }

    public static boolean isTransferTopic(String topic) {
        return topic != null && strip0x(TRANSFER_TOPIC).equalsIgnoreCase(strip0x(topic));
    }

    /** Left-pads a 20-byte address (hex) to a 32-byte ABI word. */
    public static String padAddress(String addressHex) {
        String h = strip0x(addressHex).toLowerCase(Locale.ROOT);
        if (h.length() != 40) throw new IllegalArgumentException("address must be 20 bytes");
        return "0".repeat(24) + h;
    }

    public static byte[] decodeHex(String hex) {
        String h = strip0x(hex);
        if (h.length() % 2 != 0) throw new IllegalArgumentException("odd hex length");
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(h.charAt(2 * i), 16), lo = Character.digit(h.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("invalid hex");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    public static String encodeHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }
}
