package io.openchain.blockchain.indexer;

import io.openchain.core.model.Network;

/**
 * No common ancestor inside the rollback window: automatic recovery is unsafe (events beyond the window can
 * no longer be retracted). Requires operator action (resync from an earlier block). State is left untouched.
 */
public final class ReorgTooDeepException extends RuntimeException {
    private final Network network;
    private final long atBlock;
    private final int windowSize;

    public ReorgTooDeepException(Network network, long atBlock, int windowSize) {
        super("reorg on " + network.id() + " at block " + atBlock + " deeper than the rollback window (" + windowSize + " blocks)");
        this.network = network;
        this.atBlock = atBlock;
        this.windowSize = windowSize;
    }

    public Network network() { return network; }
    public long atBlock() { return atBlock; }
    public int windowSize() { return windowSize; }
}
