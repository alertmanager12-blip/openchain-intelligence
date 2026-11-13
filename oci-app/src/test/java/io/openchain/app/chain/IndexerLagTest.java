package io.openchain.app.chain;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.core.model.Network;
import io.openchain.core.model.NetworkStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The indexing lag is unknown, not 0, when the chain head is unknown: observed on 2026-11-12, when TronGrid answered
 * HTTP 429 and the gauge showed "no lag" while indexing was stopped.
 */
class IndexerLagTest {
    static NetworkStatus status(long head, boolean healthy) {
        return new NetworkStatus(Network.TRON, head, Math.max(0, head - 19), healthy, "p", 5, Instant.EPOCH, healthy ? null : "HTTP 429");
    }

    @Test void lagIsTheDistanceToAKnownHead() {
        assertThat(ChainIngestionService.indexerLag(status(1_000, true), 990)).hasValue(10);
        assertThat(ChainIngestionService.indexerLag(status(1_000, true), 1_000)).hasValue(0);
        assertThat(ChainIngestionService.indexerLag(status(1_000, true), 1_005)).hasValue(0); // head read slightly earlier
        assertThat(ChainIngestionService.indexerLag(status(1_000, true), -1)).hasValue(1_000); // nothing indexed yet
    }

    @Test void lagIsUnknownWhenTheHeadIsUnknown() {
        assertThat(ChainIngestionService.indexerLag(null, 990)).isEmpty();                 // no status yet
        assertThat(ChainIngestionService.indexerLag(status(0, false), 990)).isEmpty();     // provider failing: head 0
        assertThat(ChainIngestionService.indexerLag(status(1_000, false), 990)).isEmpty(); // provider failing: stale head
        assertThat(ChainIngestionService.indexerLag(status(0, true), 990)).isEmpty();
    }
}
