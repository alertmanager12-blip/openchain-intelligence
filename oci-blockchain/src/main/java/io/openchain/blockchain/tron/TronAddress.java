package io.openchain.blockchain.tron;

import io.openchain.blockchain.evm.EvmCodec;

/** TRON address codec: base58check ("T...") <-> 21-byte hex with 0x41 prefix. */
public final class TronAddress {
    public static final byte PREFIX = 0x41;

    private TronAddress() {}

    /** "T..." -> "41" + 40 hex chars, lowercase. */
    public static String toHex(String base58) {
        byte[] p = Base58.decodeCheck(base58);
        if (p.length != 21 || p[0] != PREFIX) throw new IllegalArgumentException("not a TRON mainnet address");
        return EvmCodec.encodeHex(p);
    }

    /** Accepts "41"+40 hex, 40 hex (EVM-style 20 bytes, as in TVM logs) or either with 0x prefix. */
    public static String fromHex(String hex) {
        String h = EvmCodec.strip0x(hex).toLowerCase();
        if (h.length() == 40) h = "41" + h;
        if (h.length() != 42 || !h.startsWith("41")) throw new IllegalArgumentException("invalid TRON hex address");
        return Base58.encodeCheck(EvmCodec.decodeHex(h));
    }

    public static boolean isValid(String base58) {
        try { toHex(base58); return true; } catch (IllegalArgumentException e) { return false; }
    }

    /** 32-byte log topic -> base58 address. */
    public static String fromTopic(String topic) { return fromHex(EvmCodec.topicToAddressHex(topic)); }
}
