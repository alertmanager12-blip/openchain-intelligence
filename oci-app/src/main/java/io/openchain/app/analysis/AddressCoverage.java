package io.openchain.app.analysis;

import io.openchain.app.chain.IndexingCoverage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Which blocks an answer about one address rests on (Fund Flow Trace P0-B, {@code docs/FUND_FLOW_TRACE_P0_B_COVERAGE.md}
 * § 2; ADR-0023 amendment 1: coverage is separate from the evidence level). Pure.
 * <p>{@code COMPLETE} only when every condition holds, else {@code PARTIAL} with the reasons:
 * <ol>
 * <li>the address history is COMPLETE for token transfers and native transfers;</li>
 * <li>it reaches, without a hole, the first block indexed live in this run;</li>
 * <li>no recorded indexing gap falls in the live range;</li>
 * <li>live indexing has indexed at least one block.</li>
 * </ol>
 * An address whose history is loaded is exempt from the working-set trim, so its flows are all in memory. Kinds of
 * transfers that no source covers (EVM internal transactions, TRC-10) are limits whatever the state.
 */
public final class AddressCoverage {
    private AddressCoverage() {}

    public enum State { COMPLETE, PARTIAL, UNAVAILABLE }

    /** History of the address as stored: status and covered block range, and which kinds the source covers. */
    public record History(String status, Long fromBlock, Long toBlock, Instant fromTime, boolean tokenTransfers, boolean native_,
                          String message) {
        boolean complete() { return "COMPLETE".equals(status) && fromBlock != null && toBlock != null; }
    }

    public record Segment(String source, Long fromBlock, Long toBlock, Instant fromTime, String state, String reason) {}

    public record Result(State state, Long fromBlock, Long toBlock, List<Segment> segments, List<IndexingCoverage.Gap> knownGaps,
                         List<String> reasons) {}

    /**
     * @param history          the address history, or null when no history source is configured for the network
     * @param firstLiveBlock   first block indexed live in this run (-1: none yet)
     * @param lastLiveBlock    last block indexed live (-1: none yet)
     * @param gaps             recorded indexing gaps of the network
     * @param trimmedAddress   true when the address is not exempt from the working-set trim and flows were trimmed
     */
    public static Result compute(History history, long firstLiveBlock, long lastLiveBlock, List<IndexingCoverage.Gap> gaps,
                                 boolean trimmedAddress) {
        List<Segment> segments = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        boolean complete = true;

        if (history == null) {
            complete = false;
            reasons.add("no address history source for this network: only blocks indexed live are covered");
        } else {
            boolean kinds = history.tokenTransfers() && history.native_();
            segments.add(new Segment("HISTORY", history.fromBlock(), history.toBlock(), history.fromTime(), history.status(), history.message()));
            if (!history.complete()) {
                complete = false;
                reasons.add("address history " + history.status());
            } else if (!kinds) {
                complete = false;
                reasons.add("the address history source does not cover " + (history.tokenTransfers() ? "native transfers" : "token transfers"));
            }
        }

        List<IndexingCoverage.Gap> inRange = new ArrayList<>();
        if (firstLiveBlock < 0 || lastLiveBlock < firstLiveBlock) {
            complete = false;
            reasons.add("no block indexed live yet");
        } else {
            segments.add(new Segment("LIVE", firstLiveBlock, lastLiveBlock, null, "INDEXED", null));
            for (IndexingCoverage.Gap g : gaps) {
                long lo = history != null && history.fromBlock() != null ? Math.min(history.fromBlock(), firstLiveBlock) : firstLiveBlock;
                if (g.toBlock() >= lo && g.fromBlock() <= lastLiveBlock) inRange.add(g);
            }
            if (!inRange.isEmpty()) {
                complete = false;
                reasons.add(inRange.size() + " indexing gap(s) in the range");
            }
            if (history != null && history.complete() && history.toBlock() + 1 < firstLiveBlock) {
                complete = false;
                reasons.add("blocks " + (history.toBlock() + 1) + ".." + (firstLiveBlock - 1) + " are covered by no source");
            }
        }
        if (trimmedAddress) {
            complete = false;
            reasons.add("older flows of this address may have been removed from memory (bounded working set)");
        }

        if (segments.isEmpty()) return new Result(State.UNAVAILABLE, null, null, segments, inRange, reasons);
        Long from = history != null && history.complete() ? history.fromBlock() : firstLiveBlock >= 0 ? Long.valueOf(firstLiveBlock) : null;
        Long to = lastLiveBlock >= 0 ? Long.valueOf(lastLiveBlock) : history != null ? history.toBlock() : null;
        return new Result(complete ? State.COMPLETE : State.PARTIAL, from, to, List.copyOf(segments), List.copyOf(inRange), List.copyOf(reasons));
    }
}
