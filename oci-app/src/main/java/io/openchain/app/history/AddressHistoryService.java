package io.openchain.app.history;

import static io.openchain.core.detection.SignalText.number;

import io.openchain.app.api.Dto;
import io.openchain.app.history.AddressHistoryStore.Progress;
import io.openchain.app.history.AddressHistoryStore.Status;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.history.AddressHistorySource;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * On-demand address history (ADR-0020). When an address is analysed on a network that has an
 * {@link AddressHistorySource} and the address is not covered yet, a backfill is queued on a background thread:
 * <ul>
 *   <li>target range: {@code [toBlock - maxBlocks + 1, toBlock]} with {@code toBlock} = min(source safe head, first block
 *       indexed live - 1): the live indexer covers the blocks after it (no overlap, no double counting);</li>
 *   <li>pages are fetched newest first; each page's events and the new progress (cursor, covered range) are stored
 *       atomically, so a crash or a provider error loses nothing and the next run resumes from the cursor;</li>
 *   <li>caps: {@code maxPages} and {@code maxEvents} per address; reaching one before the range start ends the run as
 *       TRUNCATED (older history not loaded);</li>
 *   <li>when the run ends (COMPLETE or TRUNCATED), the stored events are applied to the intelligence pipeline in
 *       chronological order (profiles, detection, scoring, labels) through the history sink, which does NOT evaluate
 *       rules: history never raises alerts. Applying is idempotent (event ids).</li>
 * </ul>
 * At startup, finished backfills are re-applied from the store (no RPC) and unfinished ones are resumed.
 * Idempotent: an address already covered, queued or running is left alone; a FAILED one is resumed on the next analysis.
 */
public final class AddressHistoryService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AddressHistoryService.class);

    /**
     * @param maxBlocks lookback below the top of the range ({@code oci.chains.<network>.history.max-blocks})
     * @param maxPages  source pages per address (each EVM page = one chunk of blocks, two eth_getLogs calls)
     * @param maxEvents events stored per address
     * @param pause     pause between two pages (rate-limit friendliness)
     */
    public record Config(long maxBlocks, int maxPages, long maxEvents, Duration pause) {
        public Config {
            if (maxBlocks < 1 || maxPages < 1 || maxEvents < 1) throw new IllegalArgumentException("invalid history config");
            pause = pause == null ? Duration.ZERO : pause;
        }

        public static Config defaults() { return new Config(500_000, 200, 5_000, Duration.ofMillis(100)); }
    }

    private record Registration(AddressHistorySource source, Config config) {}

    private final AddressHistoryStore store;
    private final Consumer<List<ChainEvent>> sink;
    private final Function<Network, OptionalLong> firstLiveBlock;
    private final Clock clock;
    private final ExecutorService executor;
    private final Map<Network, Registration> sources = new ConcurrentHashMap<>();
    private final Set<String> queued = ConcurrentHashMap.newKeySet();
    private final Set<Network> simulated = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    /**
     * @param sink           applies history events to the intelligence pipeline WITHOUT rule evaluation
     * @param firstLiveBlock first block indexed by the live indexer of a network (empty when nothing indexed yet)
     */
    public AddressHistoryService(AddressHistoryStore store, Consumer<List<ChainEvent>> sink, Function<Network, OptionalLong> firstLiveBlock,
                                 Clock clock, ExecutorService executor) {
        this.store = store;
        this.sink = sink;
        this.firstLiveBlock = firstLiveBlock;
        this.clock = clock;
        this.executor = executor;
    }

    public static ExecutorService defaultExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "oci-address-history");
            t.setDaemon(true);
            return t;
        });
    }

    /** Real provider exposing per-address history; replaces any earlier registration of the network. */
    public void register(AddressHistorySource source, Config config) {
        sources.put(source.network(), new Registration(source, config));
        simulated.remove(source.network());
        log.info("address history for {}: source {} (lookback {} blocks, max {} pages / {} events per address)",
                source.network().id(), source.sourceId(), config.maxBlocks(), config.maxPages(), config.maxEvents());
    }

    /** Removes the source of a network (tests, reconfiguration); its stored progress is kept. */
    public void unregister(Network n) { sources.remove(n); }

    /** Simulated demo chain: no history source by design (the indexed demo data is the whole history). */
    public void markSimulated(Network n) { if (!sources.containsKey(n)) simulated.add(n); }

    public boolean configured(Network n) { return sources.containsKey(n); }

    /** Startup: re-applies finished backfills from the store (no RPC) and resumes unfinished ones. Returns the count re-applied. */
    public int restore() {
        int applied = 0;
        for (Progress p : store.all()) {
            if (p.status().terminal()) {
                apply(p);
                applied++;
            } else if (p.status() != Status.FAILED && sources.containsKey(p.network())) {
                submit(p.id());
            }
        }
        return applied;
    }

    /** Starts (or resumes) the backfill of an address that is not covered yet; no-op when covered, queued or not configured. */
    public void ensure(Address a) {
        if (!sources.containsKey(a.network())) return;
        String id = AddressHistoryStore.idOf(a.network(), a.value());
        Optional<Progress> p = store.load(id);
        if (p.isPresent() && p.get().status().terminal()) return;
        if (p.isEmpty()) store.save(newProgress(a, Status.PENDING));
        else if (p.get().status() == Status.FAILED) store.save(p.get().with(Status.PENDING, p.get().lastError(), clock.instant()));
        submit(id);
    }

    private void submit(String id) {
        if (closed || !queued.add(id)) return;
        try {
            executor.submit(() -> {
                try {
                    run(id);
                } finally {
                    queued.remove(id);
                }
            });
        } catch (RejectedExecutionException e) {
            queued.remove(id);
        }
    }

    /** Runs the backfill of {@code id} on the caller's thread (also used by tests). Returns the final progress. */
    public Progress run(String id) {
        Progress p = store.load(id).orElseThrow(() -> new IllegalArgumentException("unknown history backfill " + id));
        if (p.status().terminal()) return p;
        Registration reg = sources.get(p.network());
        if (reg == null) return p;
        AddressHistorySource src = reg.source();
        Config cfg = reg.config();
        Address address = new Address(p.network(), p.address());
        try {
            if (p.pages() == 0 && p.cursor() == null) p = target(p, src, cfg); // first run: resolve the range
            p = p.with(Status.RUNNING, null, clock.instant());
            store.save(p);
            while (true) {
                if (closed || Thread.currentThread().isInterrupted()) return p; // resumed at the next start
                if (p.coveredFromBlock() != null && p.coveredFromBlock() <= p.fromBlock()) {
                    p = finish(p, Status.COMPLETE);
                    break;
                }
                if (p.pages() >= cfg.maxPages() || p.eventsFound() >= cfg.maxEvents()) {
                    p = finish(p, Status.TRUNCATED);
                    break;
                }
                AddressHistorySource.HistoryPage page = src.fetch(address, p.fromBlock(), p.toBlock(), p.cursor());
                Map<String, ChainEvent> unique = new LinkedHashMap<>();
                for (ChainEvent e : page.events()) if (!e.removed()) unique.putIfAbsent(e.eventId(), e);
                long room = Math.max(0, cfg.maxEvents() - p.eventsFound());
                List<ChainEvent> kept = new ArrayList<>(unique.values());
                boolean capped = kept.size() > room;
                if (capped) {
                    // keep the newest events of the page: the stored range then has no hole at its top
                    kept.sort(InMemoryAddressHistoryStore.ORDER.reversed());
                    kept = new ArrayList<>(kept.subList(0, (int) room));
                }
                Long covered = capped ? p.coveredFromBlock()
                        : page.coveredFromBlock() > p.toBlock() ? p.coveredFromBlock() : Long.valueOf(page.coveredFromBlock());
                Instant coveredTime = capped ? p.coveredFromTime() : page.coveredFromTime() != null ? page.coveredFromTime() : p.coveredFromTime();
                p = new Progress(p.id(), p.network(), p.address(), p.source(), Status.RUNNING, p.fromBlock(), p.toBlock(), covered,
                        coveredTime, page.nextCursor(), p.eventsFound() + kept.size(), p.pages() + 1, capped, null, p.createdAt(), clock.instant());
                store.saveChunk(p, kept);
                if (capped) {
                    p = finish(p, Status.TRUNCATED);
                    break;
                }
                if (page.last()) {
                    p = new Progress(p.id(), p.network(), p.address(), p.source(), Status.RUNNING, p.fromBlock(), p.toBlock(),
                            p.fromBlock(), p.coveredFromTime(), null, p.eventsFound(), p.pages(), false, null, p.createdAt(), p.updatedAt());
                    p = finish(p, Status.COMPLETE);
                    break;
                }
                if (!cfg.pause().isZero()) Thread.sleep(cfg.pause().toMillis());
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return p;
        } catch (RuntimeException e) {
            String err = e.getClass().getSimpleName() + ": " + e.getMessage();
            p = p.with(Status.FAILED, err.length() > 500 ? err.substring(0, 500) : err, clock.instant());
            store.save(p);
            log.warn("address history {} stopped (progress kept, resumable): {}", id, err);
            return p;
        }
        log.info("address history {}: {} ({} event(s), {} page(s), covered from block {})", id, p.status(), p.eventsFound(), p.pages(),
                p.coveredFromBlock());
        return p;
    }

    private Progress target(Progress p, AddressHistorySource src, Config cfg) {
        long safe = src.historySafeHead();
        OptionalLong live = firstLiveBlock.apply(p.network());
        long to = live.isPresent() ? Math.min(safe, live.getAsLong() - 1) : safe;
        to = Math.max(0, to);
        long from = Math.max(0, to - cfg.maxBlocks() + 1);
        return new Progress(p.id(), p.network(), p.address(), src.sourceId(), p.status(), from, to, null, null, null, 0, 0, false,
                null, p.createdAt(), clock.instant());
    }

    /** Applies the stored events, THEN records the terminal status: a client never sees COMPLETE with half-applied data. */
    private Progress finish(Progress p, Status s) {
        Progress done = p.with(s, null, clock.instant());
        apply(done);
        store.save(done);
        return done;
    }

    private void apply(Progress p) {
        List<ChainEvent> events = store.events(p.id());
        if (!events.isEmpty()) sink.accept(events);
    }

    private Progress newProgress(Address a, Status s) {
        Instant now = clock.instant();
        Registration r = sources.get(a.network());
        return new Progress(AddressHistoryStore.idOf(a.network(), a.value()), a.network(), a.value(),
                r == null ? "none" : r.source().sourceId(), s, 0, 0, null, null, null, 0, 0, false, null, now, now);
    }

    // ---- API view -----------------------------------------------------------------------------------------------

    public Dto.HistoryCoverageDto coverage(Address a) {
        Network n = a.network();
        Registration reg = sources.get(n);
        if (reg == null) {
            String msg = simulated.contains(n)
                    ? "Simulated demo chain: the indexed demo data is the whole history; no address history source is used."
                    : "No address history source is configured for " + n.id() + ": only activity indexed live is shown (ADR-0020).";
            return new Dto.HistoryCoverageDto("NOT_CONFIGURED", List.of(), null, null, null, false, false, false, false, false, 0,
                    msg, null);
        }
        AddressHistorySource.Coverage c = reg.source().coverage();
        Optional<Progress> po = store.load(AddressHistoryStore.idOf(n, a.value()));
        if (po.isEmpty()) {
            return new Dto.HistoryCoverageDto("PENDING", List.of(reg.source().sourceId()), null, null, null, c.tokenTransfers(),
                    c.approvals(), c.nativeTransfers(), c.internalTransfers(), false, 0, "Loading history…", null);
        }
        Progress p = po.get();
        String status = p.status().name();
        String msg = switch (p.status()) {
            case PENDING -> "Loading history… (queued)";
            case RUNNING -> "Loading history… " + number(java.math.BigDecimal.valueOf(p.eventsFound())) + " event(s) found so far"
                    + (p.coveredFromBlock() == null ? "." : ", back to block " + number(java.math.BigDecimal.valueOf(p.coveredFromBlock())) + ".");
            case FAILED -> "History loading failed (" + p.lastError() + "). Progress is kept; it resumes the next time this address is analysed.";
            case COMPLETE, TRUNCATED -> covered(p, c, reg.source().sourceId(), reg.config());
        };
        return new Dto.HistoryCoverageDto(status, List.of(p.source()), p.coveredFromBlock(), p.pages() == 0 ? null : p.toBlock(),
                p.coveredFromTime(), c.tokenTransfers(), c.approvals(), c.nativeTransfers(), c.internalTransfers(), p.truncated(),
                p.eventsFound(), msg, p.updatedAt());
    }

    private static String covered(Progress p, AddressHistorySource.Coverage c, String source, Config cfg) {
        List<String> kinds = new ArrayList<>();
        String token = p.network() == Network.TRON ? "TRC-20" : "ERC-20";
        if (c.tokenTransfers()) kinds.add(token + " transfers");
        if (c.nativeTransfers()) kinds.add("native transfers");
        if (c.approvals()) kinds.add("approvals");
        String since = p.coveredFromTime() != null ? "since " + LocalDate.ofInstant(p.coveredFromTime(), ZoneOffset.UTC)
                : p.coveredFromBlock() != null ? "since block " + number(java.math.BigDecimal.valueOf(p.coveredFromBlock())) : "for the recent blocks";
        StringBuilder sb = new StringBuilder("History: ").append(join(kinds)).append(' ').append(since).append(" (").append(source)
                .append(", ").append(number(java.math.BigDecimal.valueOf(p.eventsFound()))).append(" event(s))");
        if (p.status() == Status.TRUNCATED) {
            sb.append("; truncated at ").append(p.eventsFound() >= cfg.maxEvents() ? cfg.maxEvents() + " events" : cfg.maxPages() + " pages")
                    .append(": older history is not loaded");
        }
        sb.append('.');
        List<String> missing = new ArrayList<>();
        if (!c.nativeTransfers()) missing.add("native transfers");
        if (!c.internalTransfers()) missing.add("internal transfers");
        if (!c.approvals()) missing.add("approvals");
        if (!missing.isEmpty()) {
            String m = join(missing);
            sb.append(' ').append(Character.toUpperCase(m.charAt(0))).append(m.substring(1)).append(" are not covered");
            if (p.network() != Network.TRON) sb.append(": configure an Erigon node (ADR-0020)");
            sb.append('.');
        }
        return sb.toString();
    }

    private static String join(List<String> l) {
        if (l.size() <= 1) return String.join("", l);
        return String.join(", ", l.subList(0, l.size() - 1)) + " and " + l.getLast();
    }

    @Override public void close() {
        closed = true;
        executor.shutdownNow();
    }
}
