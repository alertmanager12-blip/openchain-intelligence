package io.openchain.app.chain;

import io.openchain.core.model.Network;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What live indexing has seen, per network (Fund Flow Trace P0-B): the block ranges skipped at a resume, and the first
 * block of the current run of continuous indexing. Metadata only. Memory by default, PostgreSQL ({@code V13}) when
 * persistence is enabled.
 */
public final class IndexingCoverage {

    public enum Cause { RESUME_GAP }

    /** Block range NOT indexed, inclusive. */
    public record Gap(Network network, long fromBlock, long toBlock, Cause cause, Instant detectedAt) {}

    /**
     * First block of continuous live indexing. {@code exact = false}: set at the first start after the migration on a
     * network that was already indexed; earlier blocks may have been indexed, but their continuity is unknown.
     */
    public record LiveStart(Network network, long fromBlock, boolean exact, Instant since) {}

    /** Storage. */
    public interface Store {
        void addGap(Gap gap);
        List<Gap> gaps(Network n);
        Optional<LiveStart> liveStart(Network n);
        void setLiveStart(LiveStart s);
    }

    /** In-memory store (lost on restart, like the in-memory checkpoints). */
    public static final class MemoryStore implements Store {
        private final Map<Network, List<Gap>> gaps = new ConcurrentHashMap<>();
        private final Map<Network, LiveStart> starts = new ConcurrentHashMap<>();
        @Override public synchronized void addGap(Gap g) {
            List<Gap> l = gaps.computeIfAbsent(g.network(), k -> new ArrayList<>());
            if (l.stream().noneMatch(x -> x.fromBlock() == g.fromBlock())) l.add(g);
        }
        @Override public synchronized List<Gap> gaps(Network n) { return List.copyOf(gaps.getOrDefault(n, List.of())); }
        @Override public Optional<LiveStart> liveStart(Network n) { return Optional.ofNullable(starts.get(n)); }
        @Override public void setLiveStart(LiveStart s) { starts.put(s.network(), s); }
    }

    private IndexingCoverage() {}

    /**
     * Records what a (re)start of live indexing means for coverage and returns the block indexing resumes at.
     *
     * @param lastIndexed last block of the saved checkpoint, or null on a first start
     * @param start       block the indexer would start at, computed from the head
     * @param maxGap      max-resume-gap ({@code <= 0}: never skip)
     */
    public static long onStart(Store store, Network n, Long lastIndexed, long start, long maxGap, Instant now) {
        if (lastIndexed == null) {
            store.setLiveStart(new LiveStart(n, start, true, now));
            return start;
        }
        if (maxGap > 0 && start - lastIndexed > maxGap) {
            store.addGap(new Gap(n, lastIndexed + 1, start - 1, Cause.RESUME_GAP, now));
            store.setLiveStart(new LiveStart(n, start, true, now));
            return start;
        }
        if (store.liveStart(n).isEmpty()) store.setLiveStart(new LiveStart(n, lastIndexed + 1, false, now));
        return lastIndexed + 1;
    }
}
