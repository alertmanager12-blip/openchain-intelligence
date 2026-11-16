package io.openchain.app.chain;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.core.model.Network;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Fund Flow Trace P0-B: what a (re)start of live indexing records about coverage (cases C3 and C8). */
class IndexingCoverageTest {
    static final Instant T = Instant.parse("2026-11-16T12:00:00Z");
    final IndexingCoverage.MemoryStore store = new IndexingCoverage.MemoryStore();

    @Test void aFirstStartIsAnExactBeginningOfLiveCoverage() {
        assertThat(IndexingCoverage.onStart(store, Network.TRON, null, 1_000, 7_200, T)).isEqualTo(1_000);
        assertThat(store.liveStart(Network.TRON)).hasValue(new IndexingCoverage.LiveStart(Network.TRON, 1_000, true, T));
        assertThat(store.gaps(Network.TRON)).isEmpty();
    }

    /** C3: the skipped range is recorded exactly and continuous coverage restarts after it. */
    @Test void aResumeGapIsRecordedAndContinuousCoverageRestartsAfterIt() {
        IndexingCoverage.onStart(store, Network.TRON, null, 1_000, 7_200, T);
        assertThat(IndexingCoverage.onStart(store, Network.TRON, 5_000L, 20_000, 7_200, T.plusSeconds(60))).isEqualTo(20_000);
        assertThat(store.gaps(Network.TRON)).containsExactly(
                new IndexingCoverage.Gap(Network.TRON, 5_001, 19_999, IndexingCoverage.Cause.RESUME_GAP, T.plusSeconds(60)));
        assertThat(store.liveStart(Network.TRON).orElseThrow().fromBlock()).isEqualTo(20_000);
    }

    /** C8: an ordinary restart keeps the continuous run (the indexer resumes after its checkpoint). */
    @Test void anOrdinaryRestartKeepsTheContinuousRun() {
        IndexingCoverage.onStart(store, Network.ETHEREUM, null, 1_000, 7_200, T);
        assertThat(IndexingCoverage.onStart(store, Network.ETHEREUM, 1_500L, 1_600, 7_200, T.plusSeconds(60))).isEqualTo(1_501);
        assertThat(store.liveStart(Network.ETHEREUM).orElseThrow()).isEqualTo(new IndexingCoverage.LiveStart(Network.ETHEREUM, 1_000, true, T));
        assertThat(store.gaps(Network.ETHEREUM)).isEmpty();
    }

    /** Networks already indexed before the migration: continuity before the resume point is unknown, and said so. */
    @Test void theFirstStartAfterTheMigrationIsNotPresentedAsExact() {
        IndexingCoverage.onStart(store, Network.ETHEREUM, 1_500L, 1_600, 7_200, T);
        assertThat(store.liveStart(Network.ETHEREUM)).hasValue(new IndexingCoverage.LiveStart(Network.ETHEREUM, 1_501, false, T));
    }

    @Test void withoutAMaxGapNothingIsSkipped() {
        IndexingCoverage.onStart(store, Network.TRON, 5_000L, 900_000, 0, T);
        assertThat(store.gaps(Network.TRON)).isEmpty();
    }
}
