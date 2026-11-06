package io.openchain.app.chain;

import io.openchain.blockchain.backfill.FreezeBackfill;
import io.openchain.blockchain.backfill.FreezeBackfillStore;
import io.openchain.blockchain.backfill.FreezeLogScanner;
import io.openchain.blockchain.transport.Sleeper;
import io.openchain.core.model.Network;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

/**
 * Freeze backfills of the networks that have a real provider and {@code oci.chains.<network>.freeze-backfill.from-block}.
 * Registration re-applies the freezes stored by earlier runs (no RPC; label state is in memory); runs execute one at a
 * time on a dedicated background thread, triggered at startup and/or by {@code POST /api/v1/admin/backfill/freezes}.
 * Progress and found freezes are persisted by the {@link FreezeBackfillStore} (PostgreSQL in JDBC mode).
 */
@Service
public class FreezeBackfillService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(FreezeBackfillService.class);

    public enum State { IDLE, QUEUED, RUNNING, COMPLETED, CANCELLED, FAILED }

    /** API view of one network's backfill. */
    public record Status(String network, State state, long fromBlock, Long toBlock, int chunkSize, Long lastScannedBlock,
                         long freezesFound, int replayedAtStartup, FreezeBackfill.Result lastResult, String lastError,
                         Instant startedAt, Instant finishedAt) {}

    private static final class Entry {
        final Network network;
        final FreezeBackfill backfill;
        volatile State state = State.IDLE;
        volatile FreezeBackfill.Result lastResult;
        volatile String lastError;
        volatile Instant startedAt, finishedAt;
        volatile int replayed;

        Entry(Network network, FreezeBackfill backfill) { this.network = network; this.backfill = backfill; }
    }

    private final FreezeBackfillStore store;
    private final ChainIngestionService chains;
    private final Clock clock;
    private final Map<Network, Entry> entries = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "oci-freeze-backfill");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean shuttingDown;
    /** Overridable in tests. */
    volatile Sleeper sleeper = Sleeper.SYSTEM;

    public FreezeBackfillService(FreezeBackfillStore store, ChainIngestionService chains, Clock clock) {
        this.store = store;
        this.chains = chains;
        this.clock = clock;
    }

    public static String idOf(Network n) { return "freezes:" + n.id(); }

    /** Registers the backfill of a network and re-applies the freezes stored by previous runs. */
    public synchronized void register(FreezeLogScanner scanner, FreezeBackfill.Config config) {
        Network n = scanner.network();
        if (entries.containsKey(n)) throw new IllegalStateException("freeze backfill already registered for " + n.id());
        FreezeBackfill b = new FreezeBackfill(idOf(n), scanner, store, config, chains::applyBackfilledFreeze,
                d -> sleeper.sleep(d), clock);
        Entry e = new Entry(n, b);
        e.replayed = b.replayStored();
        entries.put(n, e);
        log.info("freeze backfill {}: blocks {}..{} (chunk {}), {} stored freeze event(s) re-applied", n.id(), config.fromBlock(),
                config.toBlock() == null ? "finalized head" : config.toBlock(), config.chunkSize(), e.replayed);
    }

    public boolean registered(Network n) { return entries.containsKey(n); }

    /** Queues a run; a backfill already queued or running is left alone. Returns the status after the call. */
    public synchronized Status trigger(Network n) {
        Entry e = entries.get(n);
        if (e == null) throw new IllegalArgumentException("no freeze backfill configured for " + n.id());
        if (e.state == State.QUEUED || e.state == State.RUNNING) return status(e);
        e.state = State.QUEUED;
        executor.submit(() -> run(e));
        return status(e);
    }

    /** Runs synchronously on the caller's thread (tests, tooling). */
    public Status runNow(Network n) {
        Entry e = Optional.ofNullable(entries.get(n)).orElseThrow(() -> new IllegalArgumentException("no freeze backfill for " + n.id()));
        synchronized (this) {
            if (e.state == State.QUEUED || e.state == State.RUNNING) return status(e);
            e.state = State.QUEUED;
        }
        run(e);
        return status(e);
    }

    private void run(Entry e) {
        e.state = State.RUNNING;
        e.startedAt = clock.instant();
        e.lastError = null;
        try {
            FreezeBackfill.Result r = e.backfill.run(() -> shuttingDown || Thread.currentThread().isInterrupted());
            e.lastResult = r;
            e.state = r.complete() ? State.COMPLETED : State.CANCELLED;
            log.info("freeze backfill {}: {} up to block {} ({} chunk(s), {} quer(ies), {} halving(s), {} freeze event(s) found)",
                    e.network.id(), e.state, r.lastScannedBlock(), r.chunks(), r.queries(), r.halvings(), r.freezesApplied());
        } catch (RuntimeException ex) {
            e.state = State.FAILED;
            e.lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            log.warn("freeze backfill {} stopped (progress kept, resumable): {}", e.network.id(), e.lastError);
        } finally {
            e.finishedAt = clock.instant();
        }
    }

    public List<Status> statuses() {
        return entries.values().stream().sorted(Comparator.comparing(x -> x.network)).map(this::status).toList();
    }

    public Optional<Status> status(Network n) { return Optional.ofNullable(entries.get(n)).map(this::status); }

    private Status status(Entry e) {
        var p = store.load(e.backfill.id());
        FreezeBackfill.Config c = e.backfill.config();
        return new Status(e.network.id(), e.state, c.fromBlock(), c.toBlock(), c.chunkSize(),
                p.map(FreezeBackfillStore.Progress::lastScannedBlock).orElse(null), p.map(FreezeBackfillStore.Progress::freezesFound).orElse(0L),
                e.replayed, e.lastResult, e.lastError, e.startedAt, e.finishedAt);
    }

    @Override public void destroy() {
        shuttingDown = true;
        executor.shutdownNow();
    }
}
