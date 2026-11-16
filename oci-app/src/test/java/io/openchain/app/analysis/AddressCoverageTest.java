package io.openchain.app.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.app.analysis.AddressCoverage.History;
import io.openchain.app.analysis.AddressCoverage.State;
import io.openchain.app.chain.IndexingCoverage;
import io.openchain.core.model.Network;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Fund Flow Trace P0-B, cases C1 to C6 of docs/FUND_FLOW_TRACE_P0_B_COVERAGE.md § 4 (C7: TransferIdentity, C8: IndexingCoverage). */
class AddressCoverageTest {
    static final Instant T = Instant.parse("2026-11-01T00:00:00Z");

    static History history(String status, long from, long to) { return new History(status, from, to, T, true, true, null); }

    static IndexingCoverage.Gap gap(long from, long to) {
        return new IndexingCoverage.Gap(Network.TRON, from, to, IndexingCoverage.Cause.RESUME_GAP, T);
    }

    /** C1: complete history joined to continuous live indexing. */
    @Test void c1CompleteHistoryJoinedToLiveIndexingIsComplete() {
        var r = AddressCoverage.compute(history("COMPLETE", 500, 999), 1_000, 1_200, List.of(), false);
        assertThat(r.state()).isEqualTo(State.COMPLETE);
        assertThat(r.fromBlock()).isEqualTo(500);
        assertThat(r.toBlock()).isEqualTo(1_200);
        assertThat(r.reasons()).isEmpty();
    }

    /** C2: a truncated history is never complete, and says so. */
    @Test void c2ATruncatedHistoryIsPartial() {
        var r = AddressCoverage.compute(history("TRUNCATED", 800, 999), 1_000, 1_200, List.of(), false);
        assertThat(r.state()).isEqualTo(State.PARTIAL);
        assertThat(r.reasons()).anyMatch(s -> s.contains("TRUNCATED"));
    }

    /** C3: a recorded indexing gap in the range makes it partial and is listed exactly. */
    @Test void c3AnIndexingGapInTheRangeIsListed() {
        var r = AddressCoverage.compute(history("COMPLETE", 500, 999), 1_000, 1_200, List.of(gap(1_050, 1_099), gap(10, 20)), false);
        assertThat(r.state()).isEqualTo(State.PARTIAL);
        assertThat(r.knownGaps()).containsExactly(gap(1_050, 1_099));
    }

    /** C4: never looked up, only a few hours in memory: live segment only, partial. */
    @Test void c4NoHistoryLoadedIsPartialOnTheLiveSegment() {
        var r = AddressCoverage.compute(history("PENDING", 0, 0), 1_000, 1_200, List.of(), true);
        assertThat(r.state()).isEqualTo(State.PARTIAL);
        assertThat(r.segments()).extracting(AddressCoverage.Segment::source).contains("LIVE");
        assertThat(r.reasons()).anyMatch(s -> s.contains("removed from memory"));
    }

    /** C5: nothing indexed live yet. */
    @Test void c5NothingIndexedLiveYetIsNeverComplete() {
        var r = AddressCoverage.compute(history("COMPLETE", 500, 999), -1, -1, List.of(), false);
        assertThat(r.state()).isEqualTo(State.PARTIAL);
        assertThat(r.reasons()).contains("no block indexed live yet");
    }

    /** C6: no history source for the network: only the live blocks. */
    @Test void c6NoHistorySourceCoversOnlyTheLiveBlocks() {
        var r = AddressCoverage.compute(null, 1_000, 1_200, List.of(), false);
        assertThat(r.state()).isEqualTo(State.PARTIAL);
        assertThat(r.fromBlock()).isEqualTo(1_000);
        assertThat(AddressCoverage.compute(null, -1, -1, List.of(), false).state()).isEqualTo(State.UNAVAILABLE);
    }

    @Test void aHoleBetweenHistoryAndLiveIndexingIsNamed() {
        var r = AddressCoverage.compute(history("COMPLETE", 500, 900), 1_000, 1_200, List.of(), false);
        assertThat(r.state()).isEqualTo(State.PARTIAL);
        assertThat(r.reasons()).contains("blocks 901..999 are covered by no source");
    }

    /** Ethereum history (eth_getLogs) has no native transfers: never complete. */
    @Test void aHistoryWithoutNativeTransfersIsNotComplete() {
        var r = AddressCoverage.compute(new History("COMPLETE", 500L, 999L, T, true, false, null), 1_000, 1_200, List.of(), false);
        assertThat(r.state()).isEqualTo(State.PARTIAL);
        assertThat(r.reasons()).anyMatch(s -> s.contains("native transfers"));
    }
}
