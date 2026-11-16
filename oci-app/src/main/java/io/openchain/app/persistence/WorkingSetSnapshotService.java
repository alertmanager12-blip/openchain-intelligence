package io.openchain.app.persistence;

import io.openchain.app.chain.ChainIngestionService;
import io.openchain.intelligence.IntelligencePipeline;
import io.openchain.intelligence.snapshot.WorkingSetSnapshot;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Restart without loss of the intelligence working set (ADR-0024 step 3d, docs/PERSISTENCE_SNAPSHOT_DESIGN.md).
 * <ul>
 *   <li>{@link #writeAtShutdown}: with the indexers stopped, writes the working set under the ingestion lock, tied to the
 *       indexer checkpoints (a generation whose checkpoints moved during the write is discarded);</li>
 *   <li>{@link #writePeriodic}: while indexing, takes a consistent copy at a block boundary of every network (to a local
 *       temporary file, polls waiting meanwhile), with the checkpoints of that instant as watermark, then stores it while
 *       indexing goes on;</li>
 *   <li>{@link #restoreAtStartup}: before the indexers start, reloads the last completed snapshot. Checkpoints unchanged
 *       since (orderly shutdown): the indexers resume at the next block. Checkpoints moved (a crash after a periodic
 *       snapshot): the checkpoints are put back to the snapshot's watermark and the blocks indexed since are indexed again
 *       (events are recognised: no duplicate alert). Unknown format or corrupted data: nothing is restored and the working
 *       set is rebuilt from new blocks; the reason is logged and counted. Never a partial state.</li>
 * </ul>
 * Not restored (documented): rule-engine windows, dry-run event history, recently retracted ids, freeze labels (re-applied
 * from the freeze backfill store).
 */
public final class WorkingSetSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(WorkingSetSnapshotService.class);

    public enum Outcome { RESTORED, REWOUND, NONE, SKIPPED_FORMAT, FAILED }

    private final JdbcWorkingSetSnapshotStore store;
    private final IntelligencePipeline intelligence;
    /** Runs a task under the ingestion lock (ChainIngestionService.exclusive): no event is processed meanwhile. */
    private final java.util.function.Consumer<Runnable> underIngestionLock;
    /** Runs a task at a block boundary of every network (ChainIngestionService.atBlockBoundary). */
    private final java.util.function.Consumer<Runnable> atBlockBoundary;
    private final String appVersion;
    /** Observation state saved with the working set (seen addresses, observation start); null: not saved (tests). */
    private final java.util.function.Supplier<WorkingSetSnapshot.Observation> observation;
    private final java.util.function.Predicate<WorkingSetSnapshot.Observation> installObservation;
    private volatile Boolean lastObservationRestored;
    private final Clock clock;
    private volatile Outcome lastRestore;
    private final AtomicLong writeFailures = new AtomicLong(), restoreFailures = new AtomicLong();
    private volatile long lastWriteMillis = -1, lastWriteBytes = -1, lastRestoreMillis = -1, lastPauseMillis = -1;
    private volatile WorkingSetSnapshot.Counts lastRestored;
    /** When the snapshot last restored or written was taken; null before either. */
    private volatile java.time.Instant lastSnapshotAt;

    public WorkingSetSnapshotService(JdbcWorkingSetSnapshotStore store, IntelligencePipeline intelligence, ChainIngestionService chains,
                                     String appVersion, Clock clock) {
        this(store, intelligence, chains::exclusive, chains::atBlockBoundary, chains::observation, chains::installObservation,
                appVersion, clock);
    }

    public WorkingSetSnapshotService(JdbcWorkingSetSnapshotStore store, IntelligencePipeline intelligence,
                                     java.util.function.Consumer<Runnable> underIngestionLock, String appVersion, Clock clock) {
        this(store, intelligence, underIngestionLock, underIngestionLock, null, null, appVersion, clock);
    }

    public WorkingSetSnapshotService(JdbcWorkingSetSnapshotStore store, IntelligencePipeline intelligence,
                                     java.util.function.Consumer<Runnable> underIngestionLock,
                                     java.util.function.Consumer<Runnable> atBlockBoundary,
                                     java.util.function.Supplier<WorkingSetSnapshot.Observation> observation,
                                     java.util.function.Predicate<WorkingSetSnapshot.Observation> installObservation,
                                     String appVersion, Clock clock) {
        this.observation = observation;
        this.installObservation = installObservation;
        this.store = store;
        this.intelligence = intelligence;
        this.underIngestionLock = underIngestionLock;
        this.atBlockBoundary = atBlockBoundary;
        this.appVersion = appVersion == null ? "unknown" : appVersion;
        this.clock = clock;
    }

    /** Call before any indexer starts. */
    public Outcome restoreAtStartup() {
        long t0 = System.nanoTime();
        try {
            int stale = store.deleteIncomplete();
            if (stale > 0) log.warn("working-set snapshot: {} incomplete generation(s) removed (a shutdown was cut short)", stale);
            var g = store.latestComplete();
            if (g.isEmpty()) return done(Outcome.NONE, "no snapshot", t0);
            long gen = g.get().generation();
            if (g.get().format() != WorkingSetSnapshot.FORMAT) {
                store.delete(gen);
                return done(Outcome.SKIPPED_FORMAT, "format " + g.get().format() + " written by " + g.get().appVersion()
                        + ", this version reads " + WorkingSetSnapshot.FORMAT + "; working set rebuilt from new blocks", t0);
            }
            boolean moved = !store.checkpointsUnchanged(gen);
            WorkingSetSnapshot.Contents c;
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(store.open(gen), 1 << 16))) {
                c = WorkingSetSnapshot.read(in); // parsed and checked before anything changes
            }
            String rewound = "";
            if (moved) {
                // a crash after the snapshot: back to its watermark, the blocks indexed since are indexed again
                var plan = store.rewindPlan(gen);
                store.rewindCheckpoints(gen);
                rewound = plan.stream().map(r -> r.indexerId() + " " + (r.current() == null ? "none" : r.current()) + " -> "
                        + (r.watermark() == null ? "none (starts as new)" : r.watermark())).collect(java.util.stream.Collectors.joining(", "));
                log.warn("working-set snapshot of {}: the indexers moved since (unclean shutdown); checkpoints put back to the "
                        + "snapshot, blocks indexed since are indexed again: {}", g.get().createdAt(), rewound);
            }
            WorkingSetSnapshot.Counts[] installed = new WorkingSetSnapshot.Counts[1];
            Boolean[] observed = new Boolean[1];
            underIngestionLock.accept(() -> {
                installed[0] = WorkingSetSnapshot.install(c, intelligence);
                // all or nothing: without the filter, the observation start would make evicted old wallets look new
                observed[0] = c.observation() != null && installObservation != null && installObservation.test(c.observation());
            });
            lastObservationRestored = observed[0];
            if (!observed[0]) log.warn("working-set snapshot: observation state (seen addresses, observation start) {}; wallet ages are "
                    + "withheld until the age warm-up has passed again", c.observation() == null ? "not in the snapshot"
                    : "not restored (seen-address filter size changed since the snapshot)");
            WorkingSetSnapshot.Counts n = installed[0];
            lastRestored = n;
            lastSnapshotAt = c.header().createdAt();
            lastRestoreMillis = (System.nanoTime() - t0) / 1_000_000;
            log.info("working-set snapshot restored in {} ms (generation {}, written by {} at {}): {} profile(s), {} flow(s), "
                    + "{} signal(s), {} score(s), {} balance(s); indexing resumes {}",
                    lastRestoreMillis, gen, c.header().appVersion(), c.header().createdAt(), n.profiles(), n.flows(), n.signals(),
                    n.scores(), n.balances(), moved ? "at the snapshot's watermark (" + rewound + ")" : "at the next block");
            lastRestore = moved ? Outcome.REWOUND : Outcome.RESTORED;
            return lastRestore;
        } catch (IOException | RuntimeException e) { // SnapshotFormatException included
            restoreFailures.incrementAndGet();
            return done(Outcome.FAILED, "not restored (" + e + "); working set rebuilt from new blocks", t0);
        }
    }

    private Outcome done(Outcome o, String why, long t0) {
        lastRestore = o;
        lastRestoreMillis = (System.nanoTime() - t0) / 1_000_000;
        if (o == Outcome.NONE) log.info("working-set snapshot: {}", why); else log.warn("working-set snapshot: {}", why);
        return o;
    }

    /**
     * Periodic snapshot, while indexing: the working set is copied at a block boundary of every network into a local
     * temporary file (polls wait during that copy only), with the checkpoints of that instant as watermark; the file is then
     * stored while indexing goes on, and the generation replaces the previous one. Returns true when one was completed.
     */
    public synchronized boolean writePeriodic() {
        long t0 = System.nanoTime();
        long gen = -1;
        java.nio.file.Path tmp = null;
        try {
            tmp = java.nio.file.Files.createTempFile("oci-working-set-", ".snapshot");
            java.nio.file.Path file = tmp;
            long[] size = new long[1];
            long[] pauseNanos = new long[1];
            String[] watermark = new String[1];
            WorkingSetSnapshot.Counts[] written = new WorkingSetSnapshot.Counts[1];
            atBlockBoundary.accept(() -> {
                long p0 = System.nanoTime();
                try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(java.nio.file.Files.newOutputStream(file), 1 << 16))) {
                    watermark[0] = store.currentCheckpoints(); // saved with the blocks applied to this state: no block in progress
                    java.time.Instant at = clock.instant();
                    written[0] = WorkingSetSnapshot.write(out, intelligence, new WorkingSetSnapshot.Header(appVersion, at, Map.of()),
                            observation == null ? null : observation.get());
                    out.flush();
                    size[0] = java.nio.file.Files.size(file);
                    lastSnapshotAt = at;
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                pauseNanos[0] = System.nanoTime() - p0;
            });
            gen = store.create(WorkingSetSnapshot.FORMAT, appVersion, watermark[0]);
            try (var in = java.nio.file.Files.newInputStream(file); OutputStream raw = store.chunks(gen)) {
                in.transferTo(raw);
            }
            WorkingSetSnapshot.Counts n = written[0];
            store.completePeriodic(gen, size[0], counts(n));
            lastWriteMillis = (System.nanoTime() - t0) / 1_000_000;
            lastWriteBytes = size[0];
            lastPauseMillis = pauseNanos[0] / 1_000_000;
            log.info("working-set snapshot (periodic) written in {} ms, indexing paused {} ms (generation {}, {} bytes): {} profile(s), "
                    + "{} flow(s), {} signal(s), {} score(s), {} balance(s)", lastWriteMillis, lastPauseMillis, gen, size[0],
                    n.profiles(), n.flows(), n.signals(), n.scores(), n.balances());
            return true;
        } catch (IOException | RuntimeException e) {
            writeFailures.incrementAndGet();
            log.error("periodic working-set snapshot not written ({}); the previous one is kept", e.toString());
            if (gen > 0) try { store.delete(gen); } catch (RuntimeException ignored) { /* removed at next start */ }
            return false;
        } finally {
            if (tmp != null) try { java.nio.file.Files.deleteIfExists(tmp); } catch (IOException ignored) { /* temp dir */ }
        }
    }

    private static String counts(WorkingSetSnapshot.Counts n) {
        return String.format(java.util.Locale.ROOT, "{\"profiles\":%d,\"flows\":%d,\"signals\":%d,\"scores\":%d,\"balances\":%d}",
                n.profiles(), n.flows(), n.signals(), n.scores(), n.balances());
    }

    /** Call once the indexers are stopped. Returns true when a snapshot was completed. */
    public synchronized boolean writeAtShutdown() {
        long t0 = System.nanoTime();
        long gen = -1;
        try {
            gen = store.create(WorkingSetSnapshot.FORMAT, appVersion);
            long g = gen;
            OutputStream raw = store.chunks(gen);
            long[] size = new long[1];
            WorkingSetSnapshot.Counts[] written = new WorkingSetSnapshot.Counts[1];
            underIngestionLock.accept(() -> {
                try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(raw, 1 << 16))) {
                    java.time.Instant at = clock.instant();
                    written[0] = WorkingSetSnapshot.write(out, intelligence, new WorkingSetSnapshot.Header(appVersion, at, Map.of()),
                            observation == null ? null : observation.get());
                    lastSnapshotAt = at;
                    out.flush();
                    size[0] = out.size(); // DataOutputStream stops counting at Integer.MAX_VALUE (2 GB)
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            WorkingSetSnapshot.Counts n = written[0];
            if (!store.complete(g, size[0], counts(n))) {
                writeFailures.incrementAndGet();
                log.error("working-set snapshot discarded: the indexer checkpoints moved while it was written (indexers still running?)");
                return false;
            }
            lastWriteMillis = (System.nanoTime() - t0) / 1_000_000;
            lastWriteBytes = size[0];
            log.info("working-set snapshot written in {} ms (generation {}, {} bytes): {} profile(s), {} flow(s), {} signal(s), {} score(s), {} balance(s)",
                    lastWriteMillis, g, size[0], n.profiles(), n.flows(), n.signals(), n.scores(), n.balances());
            return true;
        } catch (RuntimeException e) {
            writeFailures.incrementAndGet();
            log.error("working-set snapshot not written at shutdown ({}); the next start uses the last periodic one, if any, "
                    + "and indexes again the blocks since", e.toString());
            if (gen > 0) try { store.delete(gen); } catch (RuntimeException ignored) { /* removed at next start */ }
            return false;
        }
    }

    public Outcome lastRestore() { return lastRestore; }
    /** After a restore: whether the observation state came back with it (null before a restore). */
    public Boolean lastObservationRestored() { return lastObservationRestored; }
    public WorkingSetSnapshot.Counts lastRestored() { return lastRestored; }
    public long lastRestoreMillis() { return lastRestoreMillis; }
    public long lastWriteMillis() { return lastWriteMillis; }
    public long lastWriteBytes() { return lastWriteBytes; }
    /** How long indexing waited for the last periodic copy, -1 before one. */
    public long lastPauseMillis() { return lastPauseMillis; }
    public long writeFailures() { return writeFailures.get(); }
    public long restoreFailures() { return restoreFailures.get(); }
    /** Seconds since the snapshot last restored or written was taken (after a restore: how old the restored state was
     *  when the application stopped, plus the time since); NaN before either. */
    public double ageSeconds() {
        java.time.Instant at = lastSnapshotAt;
        return at == null ? Double.NaN : java.time.Duration.between(at, clock.instant()).toMillis() / 1000.0;
    }
}
