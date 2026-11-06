package io.openchain.core.model;

/** Supported networks. Adding a network = adding an enum constant + a {@code BlockchainProvider} adapter. */
public enum Network {
    TRON("tron", 19, 20),
    ETHEREUM("ethereum", 12, 64),
    BSC("bsc", 15, 30),
    POLYGON("polygon", 128, 256),
    BITCOIN("bitcoin", 6, 6),
    SOLANA("solana", 32, 32);

    private final String id;
    /** Blocks before an event is considered final (default; overridable by config). */
    private final int defaultConfirmations;
    /** Maximum reorg depth the indexer keeps rollback state for. */
    private final int maxReorgDepth;

    Network(String id, int defaultConfirmations, int maxReorgDepth) {
        this.id = id;
        this.defaultConfirmations = defaultConfirmations;
        this.maxReorgDepth = maxReorgDepth;
    }

    public String id() { return id; }
    public int defaultConfirmations() { return defaultConfirmations; }
    public int maxReorgDepth() { return maxReorgDepth; }

    public static Network fromId(String id) {
        for (Network n : values()) if (n.id.equalsIgnoreCase(id) || n.name().equalsIgnoreCase(id)) return n;
        throw new IllegalArgumentException("Unknown network: " + id);
    }
}
