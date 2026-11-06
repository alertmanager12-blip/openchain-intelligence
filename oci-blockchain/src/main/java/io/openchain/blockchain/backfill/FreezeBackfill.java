package io.openchain.blockchain.backfill;

import io.openchain.blockchain.transport.Sleeper;
import io.openchain.core.model.TokenFreeze;
import io.openchain.core.provider.ProviderException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Bounded, resumable scan of issuer freeze events over {@code [fromBlock, min(toBlock, safe head)]}:
 * <ul>
 *   <li>chunked ({@link Config#chunkSize()} blocks per query), halved on {@link RangeTooLargeException} down to
 *       {@link Config#minChunkSize()} (then the error propagates); the smaller size is kept for the rest of the run;</li>
 *   <li>rate-limit friendly: {@link Config#pause()} between chunks (on top of the transport's own rate limiter);</li>
 *   <li>resumable: after each chunk the found freezes and the progress are stored atomically BEFORE being applied, so a
 *       crash at any point loses nothing (stored freezes are re-applied at the next start) and a new run continues
 *       after the last scanned block;</li>
 *   <li>idempotent: freezes are applied in (block, log index) order through the same sink as live freezes, which
 *       ignores an event id already applied and any freeze older than the last one applied for that address.</li>
 * </ul>
 * Only blocks at or below the scanner's finalized head are scanned: backfilled events are never reorged.
 */
public final class FreezeBackfill {

    public static final Comparator<TokenFreeze> ORDER = Comparator.comparingLong(TokenFreeze::blockNumber)
            .thenComparingInt(TokenFreeze::logIndex).thenComparing(TokenFreeze::txHash);

    /**
     * @param toBlock      last block to scan; null = the scanner's safe (finalized) head at run time
     * @param chunkSize    blocks per query at the start of a run
     * @param minChunkSize smallest chunk before a range error is given up
     */
    public record Config(long fromBlock, Long toBlock, int chunkSize, int minChunkSize, Duration pause) {
        public Config {
            if (fromBlock < 0 || chunkSize < 1 || minChunkSize < 1 || minChunkSize > chunkSize || (toBlock != null && toBlock < fromBlock)) {
                throw new IllegalArgumentException("invalid freeze backfill config");
            }
            pause = pause == null ? Duration.ZERO : pause;
        }
    }

    /**
     * @param complete       the whole target range has been scanned
     * @param freezesApplied freezes found by THIS run (handed to the sink)
     */
    public record Result(long fromBlock, long targetBlock, long lastScannedBlock, int chunks, int halvings, int queries,
                         int freezesApplied, int finalChunkSize, boolean complete, boolean cancelled) {}

    private final String id;
    private final FreezeLogScanner scanner;
    private final FreezeBackfillStore store;
    private final Config config;
    private final Consumer<TokenFreeze> sink;
    private final Sleeper sleeper;
    private final Clock clock;

    public FreezeBackfill(String id, FreezeLogScanner scanner, FreezeBackfillStore store, Config config,
                          Consumer<TokenFreeze> sink, Sleeper sleeper, Clock clock) {
        this.id = id;
        this.scanner = scanner;
        this.store = store;
        this.config = config;
        this.sink = sink;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    public String id() { return id; }
    public Config config() { return config; }

    /** Re-applies every stored freeze (no RPC): rebuilds the in-memory label state after a restart. Returns the count. */
    public int replayStored() {
        List<TokenFreeze> stored = store.freezes(id);
        stored.forEach(sink);
        return stored.size();
    }

    /** Runs until the target range is scanned, {@code cancelled} turns true, or a provider error propagates. */
    public Result run(BooleanSupplier cancelled) {
        Optional<FreezeBackfillStore.Progress> p = store.load(id);
        long origin = config.fromBlock();
        long start = config.fromBlock();
        long found = 0;
        if (p.isPresent() && p.get().fromBlock() <= config.fromBlock()) { // same or wider range already started: resume
            origin = p.get().fromBlock();
            start = Math.max(config.fromBlock(), p.get().lastScannedBlock() + 1);
            found = p.get().freezesFound();
        } else if (p.isPresent()) { // range moved earlier: rescan from the new start (stored events are deduplicated)
            found = p.get().freezesFound();
        }
        long safe = scanner.backfillSafeHead();
        long target = config.toBlock() == null ? safe : Math.min(config.toBlock(), safe);
        long last = start - 1;
        int chunk = config.chunkSize(), chunks = 0, halvings = 0, queries = 0, applied = 0;
        while (start <= target) {
            if (cancelled.getAsBoolean()) {
                return new Result(origin, target, last, chunks, halvings, queries, applied, chunk, false, true);
            }
            long hi = Math.min(target, start + chunk - 1);
            List<TokenFreeze> batch;
            try {
                queries++;
                batch = scanner.scanFreezes(start, hi);
            } catch (RangeTooLargeException e) {
                if (chunk <= config.minChunkSize()) {
                    throw new ProviderException(id + ": blocks " + start + ".." + hi + " still too large at the minimum chunk size "
                            + config.minChunkSize() + ": " + e.getMessage(), false, e);
                }
                chunk = Math.max(config.minChunkSize(), chunk / 2);
                halvings++;
                continue;
            }
            Map<String, TokenFreeze> unique = new LinkedHashMap<>();
            for (TokenFreeze z : batch) {
                if (z.blockNumber() >= start && z.blockNumber() <= hi) unique.putIfAbsent(z.eventId(), z);
            }
            List<TokenFreeze> ordered = new ArrayList<>(unique.values());
            ordered.sort(ORDER);
            found += ordered.size();
            store.saveChunk(new FreezeBackfillStore.Progress(id, scanner.network(), origin, hi, found, clock.instant()), ordered);
            ordered.forEach(sink);
            applied += ordered.size();
            last = hi;
            start = hi + 1;
            chunks++;
            if (start <= target && !config.pause().isZero()) {
                try {
                    sleeper.sleep(config.pause());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new Result(origin, target, last, chunks, halvings, queries, applied, chunk, false, true);
                }
            }
        }
        return new Result(origin, target, Math.max(last, start - 1), chunks, halvings, queries, applied, chunk, true, false);
    }
}
