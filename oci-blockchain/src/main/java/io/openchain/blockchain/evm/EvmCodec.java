package io.openchain.blockchain.evm;

import java.math.BigInteger;
import java.util.Locale;

/** Hex/ABI helpers shared by EVM and TRON (TVM is EVM-compatible at the log level). */
public final class EvmCodec {
    // Event topics = keccak256 of the canonical event signature. Every constant below is recomputed by
    // EventTopicsTest with an independent (test-only) Keccak-256 implementation: never edit one without it.

    /** keccak256("Transfer(address,address,uint256)"). */
    public static final String TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    /** keccak256("Approval(address,address,uint256)"): ERC-20 / TRC-20 allowance (owner, spender indexed). */
    public static final String APPROVAL_TOPIC = "0x8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b925";
    /** keccak256("AddedBlackList(address)"): Tether TetherToken (Ethereum, TRON); address NOT indexed (in data). */
    public static final String ADDED_BLACKLIST_TOPIC = "0x42e160154868087d6bfdc0ca23d96a1c1cfa32f1b72ba9ba27b69b98a0d819dc";
    /** keccak256("RemovedBlackList(address)"): Tether TetherToken; address NOT indexed (in data). */
    public static final String REMOVED_BLACKLIST_TOPIC = "0xd7e9ec6e6ecd65492dce6bf513cd6867560d49544421d0783ddf06e76c24470c";
    /** keccak256("Blacklisted(address)"): Circle FiatToken (USDC); address indexed (topic 1). */
    public static final String BLACKLISTED_TOPIC = "0xffa4e6181777692565cf28528fc88fd1516ea86b56da075235fa575af6a4b855";
    /** keccak256("UnBlacklisted(address)"): Circle FiatToken (USDC); address indexed (topic 1). */
    public static final String UNBLACKLISTED_TOPIC = "0x117e3210bb9aa7d9baff172026820255c6f6c30ba8999d1c2fd88e2848137c4e";
    /** keccak256("BlockPlaced(address)"): Tether USDT0 (Polygon UChildUSDT0, WithBlockedList); address indexed. */
    public static final String BLOCK_PLACED_TOPIC = "0x406bbf2d8d145125adf1198d2cf8a67c66cc4bb0ab01c37dccd4f7c0aae1e7c7";
    /** keccak256("BlockReleased(address)"): Tether USDT0 (Polygon UChildUSDT0, WithBlockedList); address indexed. */
    public static final String BLOCK_RELEASED_TOPIC = "0x665918c9e02eb2fd85acca3969cb054fc84c138e60ec4af22ab6ef2fd4c93c27";
    /**
     * keccak256("OwnershipTransferred(address,address)"): OpenZeppelin Ownable (previousOwner, newOwner indexed: 3
     * topics); Circle FiatToken's Ownable declares the same signature WITHOUT indexed parameters (1 topic + 2 data words).
     */
    public static final String OWNERSHIP_TRANSFERRED_TOPIC = "0x8be0079c531659141344cd1fd0a4f28419497f9722a3daafe3b4186f6b6457e0";
    /** keccak256("Upgraded(address)"): EIP-1967 proxy implementation change (implementation indexed). */
    public static final String UPGRADED_TOPIC = "0xbc7cd75a20ee27fd9adebab32041f755214dbc6bffa90cc0225b39da2e5c2d3b";
    /** keccak256("AdminChanged(address,address)"): EIP-1967 proxy admin change (previousAdmin, newAdmin NOT indexed). */
    public static final String ADMIN_CHANGED_TOPIC = "0x7e644d79422f17c01e4894b5f4f588d331ebfa28653d42ae832dc59e38c9798f";
    /** Topics of contract admin changes, for log filters. */
    public static final java.util.List<String> ADMIN_CHANGE_TOPICS = java.util.List.of(OWNERSHIP_TRANSFERRED_TOPIC, UPGRADED_TOPIC,
            ADMIN_CHANGED_TOPIC);
    /** balanceOf(address) selector. */
    public static final String BALANCE_OF = "0x70a08231";

    private EvmCodec() {}

    public static String strip0x(String hex) {
        return hex != null && (hex.startsWith("0x") || hex.startsWith("0X")) ? hex.substring(2) : hex;
    }

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

    public static boolean isTransferTopic(String topic) { return sameTopic(TRANSFER_TOPIC, topic); }

    public static boolean isApprovalTopic(String topic) { return sameTopic(APPROVAL_TOPIC, topic); }

    /** Topic equality ignoring the 0x prefix and case (TRON nodes return topics without prefix). */
    public static boolean sameTopic(String a, String b) {
        return a != null && b != null && strip0x(a).equalsIgnoreCase(strip0x(b));
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
