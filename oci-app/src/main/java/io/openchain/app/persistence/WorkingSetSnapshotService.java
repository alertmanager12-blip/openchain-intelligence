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
 *   <li>{@link #restoreAtStartup}: before the indexers start, reloads the last completed snapshot only if the checkpoints
 *       are still the ones it was taken with, so the indexers resume at the next block with a matching state. Otherwise
 *       (unclean shutdown after an earlier restore, unknown format, corrupted data) nothing is restored and the working
 *       set is rebuilt from new blocks, as without snapshots; the reason is logged and counted. Never a partial state.</li>
 * </ul>
 * Not restored (documented): rule-engine windows, dry-run event history, recently retracted ids, freeze labels (re-applied
 * from the freeze backfill store).
 */
public final class WorkingSetSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(WorkingSetSnapshotService.class);

    public enum Outcome { RESTORED, NONE, SKIPPED_CHECKPOINTS_MOVED, SKIPPED_FORMAT, FAILED }

    private final JdbcWorkingSetSnapshotStore store;
    private final IntelligencePipeline intelligence;
    /** Runs a task under the ingestion lock (ChainIngestionService.exclusive): no event is processed meanwhile. */
    private final java.util.function.Consumer<Runnable> underIngestionLock;
    private final String appVersion;
    private final Clock clock;
    private volatile Outcome lastRestore;
    private final AtomicLong writeFailures = new AtomicLong(), restoreFailures = new AtomicLong();
    private volatile long lastWriteMillis = -1, lastWriteBytes = -1, lastRestoreMillis = -1;
    private volatile WorkingSetSnapshot.Counts lastRestored;
    /** When the snapshot last restored or written was taken; null before either. */
    private volatile java.time.Instant lastSnapshotAt;

    public WorkingSetSnapshotService(JdbcWorkingSetSnapshotStore store, IntelligencePipeline intelligence, ChainIngestionService chains,
                                     String appVersion, Clock clock) {
        this(store, intelligence, chains::exclusive, appVersion, clock);
    }

    public WorkingSetSnapshotService(JdbcWorkingSetSnapshotStore store, IntelligencePipeline intelligence,
                                     java.util.function.Consumer<Runnable> underIngestionLock, String appVersion, Clock clock) {
        this.store = store;
        this.intelligence = intelligence;
        this.underIngestionLock = underIngestionLock;
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
            if (!store.checkpointsUnchanged(gen)) {
                store.delete(gen);
                return done(Outcome.SKIPPED_CHECKPOINTS_MOVED, "the indexers moved since the snapshot of " + g.get().createdAt()
                        + " (unclean shutdown): not restored, working set rebuilt from new blocks", t0);
            }
            WorkingSetSnapshot.Contents c;
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(store.open(gen), 1 << 16))) {
                c = WorkingSetSnapshot.read(in);
            }
            WorkingSetSnapshot.Counts[] installed = new WorkingSetSnapshot.Counts[1];
            underIngestionLock.accept(() -> installed[0] = WorkingSetSnapshot.install(c, intelligence));
            WorkingSetSnapshot.Counts n = installed[0];
            lastRestored = n;
            lastSnapshotAt = c.header().createdAt();
            lastRestoreMillis = (System.nanoTime() - t0) / 1_000_000;
            log.info("working-set snapshot restored in {} ms (generation {}, written by {} at {}): {} profile(s), {} flow(s), "
                    + "{} signal(s), {} score(s), {} balance(s); indexing resumes at the next block",
                    lastRestoreMillis, gen, c.header().appVersion(), c.header().createdAt(), n.profiles(), n.flows(), n.signals(),
                    n.scores(), n.balances());
            lastRestore = Outcome.RESTORED;
            return Outcome.RESTORED;
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

    /** Call once the indexers are stopped. Returns true when a snapshot was completed. */
    public boolean writeAtShutdown() {
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
                    written[0] = WorkingSetSnapshot.write(out, intelligence, new WorkingSetSnapshot.Header(appVersion, at, Map.of()));
                    lastSnapshotAt = at;
                    out.flush();
                    size[0] = out.size(); // DataOutputStream stops counting at Integer.MAX_VALUE (2 GB)
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            WorkingSetSnapshot.Counts n = written[0];
            String counts = String.format(java.util.Locale.ROOT,
                    "{\"profiles\":%d,\"flows\":%d,\"signals\":%d,\"scores\":%d,\"balances\":%d}",
                    n.profiles(), n.flows(), n.signals(), n.scores(), n.balances());
            if (!store.complete(g, size[0], counts)) {
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
            log.error("working-set snapshot not written ({}); the next start rebuilds the working set from new blocks", e.toString());
            if (gen > 0) try { store.delete(gen); } catch (RuntimeException ignored) { /* removed at next start */ }
            return false;
        }
    }

    public Outcome lastRestore() { return lastRestore; }
    public WorkingSetSnapshot.Counts lastRestored() { return lastRestored; }
    public long lastRestoreMillis() { return lastRestoreMillis; }
    public long lastWriteMillis() { return lastWriteMillis; }
    public long lastWriteBytes() { return lastWriteBytes; }
    public long writeFailures() { return writeFailures.get(); }
    public long restoreFailures() { return restoreFailures.get(); }
    /** Seconds since the snapshot last restored or written was taken (after a restore: how old the restored state was
     *  when the application stopped, plus the time since); NaN before either. */
    public double ageSeconds() {
        java.time.Instant at = lastSnapshotAt;
        return at == null ? Double.NaN : java.time.Duration.between(at, clock.instant()).toMillis() / 1000.0;
    }
}
