package io.openchain.app.chain;

import io.openchain.alerting.alert.AlertService;
import io.openchain.alerting.pipeline.AlertingPipeline;
import io.openchain.app.labels.LabelCatalog;
import io.openchain.app.persistence.TransactionRunner;
import io.openchain.blockchain.indexer.BlockIndexer;
import io.openchain.blockchain.indexer.InMemoryCheckpointStore;
import io.openchain.blockchain.indexer.IndexerConfig;
import io.openchain.blockchain.indexer.TransferEventMapper;
import io.openchain.blockchain.simulated.SimulatedChainProvider;
import io.openchain.blockchain.token.TokenRegistry;
import io.openchain.core.detection.Signal;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.Address;
import io.openchain.core.model.BlockRef;
import io.openchain.core.model.TokenFreeze;
import io.openchain.core.model.Network;
import io.openchain.core.model.NetworkStatus;
import io.openchain.core.model.Token;
import io.openchain.core.provider.BlockchainProvider;
import io.openchain.core.risk.RiskScore;
import io.openchain.intelligence.IntelligencePipeline;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.LabelCategory;
import io.openchain.intelligence.profile.TokenFlowStats;
import io.openchain.intelligence.profile.WalletProfile;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs the event pipeline of ADR-0001 in-process:
 * provider -> BlockIndexer -> IntelligencePipeline (profiles, detection, scoring) -> enrichment -> AlertingPipeline.
 * Each indexed transfer (token or native) is enriched with the {@code wallet.*}/{@code counterparty.*} catalog fields
 * computed by the intelligence module before the rule engine sees it ({@code wallet.balance}: on real networks the
 * provider's balance at the end of the previous block, absent when unavailable; demo ledger balance on simulated chains); approvals get {@code wallet.*} (owner),
 * {@code counterparty.*} (spender), {@code approval.spender_is_contract} (provider code lookup, when the provider can
 * tell) and {@code approval.spender_age_days} (days since the platform first saw the spender in an indexed value flow;
 * absent when never seen); issuer freezes get {@code wallet.*} (frozen address, after its rescore) and
 * {@code freeze.known_counterparties}. Every new detection signal is also forwarded to the rule engine as a
 * {@code DETECTION_SIGNAL} event. {@code CONTRACT_ADMIN_CHANGE} events go through detection (OwnershipChangeDetector)
 * and the rule engine as indexed. Freezes found by the freeze backfill ({@link #applyBackfilledFreeze}) only update the
 * label / risk state (same path as live freezes) without rule evaluation. Ingestion is serialised (one poll at a time).
 * TODO(outbox): publish events through the outbox / Kafka instead of direct calls (ADR-0006).
 */
@Service
public class ChainIngestionService {

    private static final Logger log = LoggerFactory.getLogger(ChainIngestionService.class);
    private static final int MAX_SIGNALS_KEPT = 10_000;
    private static final int MAX_POLLS_PER_TICK = 1_000;
    private static final java.time.Duration REAL_CHAIN_TICK = java.time.Duration.ofSeconds(2);

    /** One indexed network. */
    public static final class NetworkRuntime {
        private final Network network;
        private final BlockchainProvider provider;
        private final SimulatedChainProvider simulator;
        private final DemoLedgerProvider ledger;
        private final BlockIndexer indexer;
        private final Duration blockTime;
        private volatile NetworkStatus lastStatus;
        private volatile long lastIndexedBlock = -1;
        private volatile String lastError;
        private volatile Instant lastPollAt;
        private IndexerConfig indexerConfig;

        NetworkRuntime(Network network, BlockchainProvider provider, SimulatedChainProvider simulator, DemoLedgerProvider ledger,
                       BlockIndexer indexer, Duration blockTime) {
            this.network = network;
            this.provider = provider;
            this.simulator = simulator;
            this.ledger = ledger;
            this.indexer = indexer;
            this.blockTime = blockTime;
        }

        public Network network() { return network; }
        public BlockchainProvider provider() { return provider; }
        public Optional<SimulatedChainProvider> simulator() { return Optional.ofNullable(simulator); }
        /** Demo ledger (simulated networks only): scenario injection point. */
        public Optional<DemoLedgerProvider> ledger() { return Optional.ofNullable(ledger); }
        public IndexerConfig indexerConfig() { return indexerConfig; }
        public Duration blockTime() { return blockTime; }
        public NetworkStatus lastStatus() { return lastStatus; }
        public long lastIndexedBlock() { return lastIndexedBlock; }
        public String lastError() { return lastError; }
        public Instant lastPollAt() { return lastPollAt; }
    }

    private final IntelligencePipeline intelligence;
    private final AlertingPipeline alerting;
    private final EventHistory history;
    private final LabelCatalog labels;
    private final TokenRegistry tokens;
    private final Clock clock;
    private final TransactionRunner tx;
    private final AlertService alertService;
    private final Map<Network, NetworkRuntime> runtimes = new ConcurrentHashMap<>();
    private final Deque<Signal> recentSignals = new ArrayDeque<>();
    private final AtomicLong eventsIndexed = new AtomicLong();
    private final AtomicLong transfersIndexed = new AtomicLong();
    private final AtomicLong eventsRetracted = new AtomicLong();
    private final AtomicLong backfilledFreezesApplied = new AtomicLong();
    private final AtomicLong spenderLookupFailures = new AtomicLong();
    private final TransferEventMapper backfillMapper;
    private final Set<Address> trackedWallets = ConcurrentHashMap.newKeySet();
    private final Map<Network, Long> firstIndexedBlock = new ConcurrentHashMap<>();
    /** Event time of the oldest event seen per network (live or address history): start of the platform's observation. */
    private final Map<Network, Instant> observationStart = new ConcurrentHashMap<>();
    private volatile Duration ageWarmup = Duration.ofDays(30);

    /**
     * Fields measured "since the platform first saw the address" (wallet.age_days, wallet.tx_count, counterparty.age_days,
     * approval.spender_age_days) are only emitted on a real chain once it has been observed for this long: before that,
     * every address looks new (production: 145 well-known DeFi routers raised "young spender" alerts after 3 days).
     */
    public void setAgeWarmup(Duration warmup) { this.ageWarmup = warmup == null ? Duration.ZERO : warmup; }

    /** Days the network has been observed so far (event time span), 0 when nothing was seen. */
    public long observedDays(Network n) {
        Instant start = observationStart.get(n);
        return start == null ? 0 : Math.max(0, Duration.between(start, clock.instant()).toDays());
    }

    public Duration ageWarmup() { return ageWarmup; }

    /** True when platform-observed age/activity fields are meaningful for this network at {@code at}. */
    private boolean observedLongEnough(Network n, Instant at) {
        NetworkRuntime rt = runtimes.get(n);
        if (rt == null || rt.simulator != null || rt.ledger != null) return true; // simulated chains: history from genesis
        Instant start = observationStart.get(n);
        return start != null && at != null && !Duration.between(start, at).minus(ageWarmup).isNegative();
    }
    private final AtomicLong historyEventsApplied = new AtomicLong();
    /** Real balance lookups (provider calls), cache hits and failures (field then absent). */
    private final AtomicLong balanceLookups = new AtomicLong(), balanceCacheHits = new AtomicLong(), balanceLookupFailures = new AtomicLong();
    private static final int BALANCE_CACHE_SIZE = 10_000;
    /** (address, token, block) -> balance at the end of that block; empty = unknown (failure or unsupported). */
    private final Map<String, Optional<BigDecimal>> balanceCache = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(1024, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Optional<BigDecimal>> eldest) {
                    return size() > BALANCE_CACHE_SIZE;
                }
            });

    public ChainIngestionService(IntelligencePipeline intelligence, AlertingPipeline alerting, EventHistory history,
                                 LabelCatalog labels, TokenRegistry tokens, Clock clock, TransactionRunner tx,
                                 AlertService alertService) {
        this.tx = tx;
        this.alertService = alertService;
        this.intelligence = intelligence;
        this.alerting = alerting;
        this.history = history;
        this.labels = labels;
        this.tokens = tokens;
        this.clock = clock;
        this.backfillMapper = new TransferEventMapper(tokens);
    }

    // ---- registration -----------------------------------------------------------------------------------------

    public synchronized NetworkRuntime register(BlockchainProvider provider, SimulatedChainProvider simulator,
                                                DemoLedgerProvider ledger, IndexerConfig config, Duration blockTime) {
        Network n = provider.network();
        if (runtimes.containsKey(n)) throw new IllegalStateException("network already registered: " + n.id());
        // TODO(persistence): PostgreSQL checkpoint store; in memory, indexing restarts from the configured start block
        // (alert creation is idempotent on replayed events, see AlertService#onMatch)
        BlockIndexer indexer = new BlockIndexer(provider, new InMemoryCheckpointStore(), new io.openchain.blockchain.indexer.ChainEventSink() {
            @Override public void emit(ChainEvent e) { onEvent(e); }
            @Override public void prepare(List<ChainEvent> blockEvents) { prefetchBalances(blockEvents); }
        }, config, tokens, clock);
        NetworkRuntime rt = new NetworkRuntime(n, provider, simulator, ledger, indexer, blockTime);
        rt.indexerConfig = config;
        runtimes.put(n, rt);
        // real chain: balances come from the provider, never from flows seen since the indexer start
        if (simulator == null) intelligence.balances().markAuthoritative(n);
        log.info("network {} registered (provider {}, start block {}, {} confirmations)", n.id(), provider.id(),
                config.startBlock(), config.confirmations());
        return rt;
    }

    public Optional<NetworkRuntime> runtime(Network n) { return Optional.ofNullable(runtimes.get(n)); }

    public Collection<NetworkRuntime> runtimes() {
        return runtimes.values().stream().sorted((a, b) -> a.network().compareTo(b.network())).toList();
    }

    public Optional<BlockchainProvider> provider(Network n) { return runtime(n).map(NetworkRuntime::provider); }

    // ---- polling ----------------------------------------------------------------------------------------------

    /** Indexes everything currently available on one network (bounded), recording errors instead of throwing. */
    public void poll(NetworkRuntime rt) {
        synchronized (rt) { pollLocked(rt); }
    }

    private void pollLocked(NetworkRuntime rt) {
        rt.lastPollAt = clock.instant();
        try {
            // head known BEFORE indexing, so real balance lookups can be skipped while catching up (withinBalanceBudget)
            rt.lastStatus = rt.provider.getNetworkStatus();
            // a real chain yields after REAL_CHAIN_TICK so other networks (same scheduler thread, same lock) keep up;
            // simulated and demo chains drain everything available, as before
            boolean real = rt.simulator == null && rt.ledger == null;
            long deadline = System.nanoTime() + REAL_CHAIN_TICK.toNanos();
            for (int i = 0; i < MAX_POLLS_PER_TICK; i++) {
                BlockIndexer.PollResult r = rt.indexer.poll();
                rt.lastIndexedBlock = r.lastBlock();
                if (r.reorgs() > 0) reorgCount.merge(rt.network(), (long) r.reorgs(), Long::sum);
                if (r.blocksIndexed() == 0) break;
                if (real && System.nanoTime() > deadline) break;
            }
            rt.lastStatus = rt.provider.getNetworkStatus();
            rt.lastError = null;
        } catch (RuntimeException e) {
            rt.lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("indexing {} failed: {}", rt.network().id(), rt.lastError);
        }
    }

    /** Current status (refreshed from the provider; falls back to the last known one). */
    public NetworkStatus status(NetworkRuntime rt) {
        try {
            NetworkStatus s = rt.provider.getNetworkStatus();
            rt.lastStatus = s;
            return s;
        } catch (RuntimeException e) {
            NetworkStatus last = rt.lastStatus;
            return new NetworkStatus(rt.network(), last == null ? 0 : last.headBlock(), last == null ? 0 : last.finalizedBlock(),
                    false, rt.provider.id(), 0, clock.instant(), "provider error: " + e.getMessage());
        }
    }

    // ---- event pipeline ---------------------------------------------------------------------------------------

    /**
     * Indexer sink. Package-visible for tests. Networks are polled concurrently (one lock per network, see
     * {@link #poll}); the real balance lookup (an RPC round trip) happens here, outside the service lock, and the
     * processing itself is serialised by {@link #process}.
     */
    void onEvent(ChainEvent e) {
        Optional<BigDecimal> balance = Optional.empty();
        if (!e.removed() && (e.type() == ChainEvent.EventType.TOKEN_TRANSFER || e.type() == ChainEvent.EventType.NATIVE_TRANSFER)
                && intelligence.balances().authoritative(e.network())) {
            Optional<ValueFlow> flow = toFlow(e);
            if (flow.isPresent()) balance = realBalanceBefore(flow.get().from(), flow.get().token(), e.blockNumber());
        }
        process(e, balance);
    }

    private synchronized void process(ChainEvent e, Optional<BigDecimal> prefetchedBalance) {
        if (e.removed()) eventsRetracted.incrementAndGet(); else eventsIndexed.incrementAndGet();
        if (!e.removed() && e.blockNumber() >= 0) firstIndexedBlock.merge(e.network(), e.blockNumber(), Math::min);
        if (!e.removed() && e.eventTime() != null) observationStart.merge(e.network(), e.eventTime(), (a, b) -> a.isBefore(b) ? a : b);
        if (e.removed()) {
            if (isFreeze(e)) intelligence.onEvent(e); // reverts the label state of a reorged freeze/unfreeze
            history.record(e);
            evaluate(e);
            return;
        }
        List<Signal> fresh = new ArrayList<>();
        ChainEvent enriched = e;
        if (e.type() == ChainEvent.EventType.TOKEN_TRANSFER || e.type() == ChainEvent.EventType.NATIVE_TRANSFER) {
            Optional<ValueFlow> flow = toFlow(e);
            if (flow.isPresent()) {
                ValueFlow f = flow.get();
                transfersIndexed.incrementAndGet();
                trackedWallets.add(f.from());
                trackedWallets.add(f.to());
                WalletProfile senderBefore = intelligence.profiles().profileOrEmpty(f.from());
                Optional<BigDecimal> realBalance = Optional.empty();
                boolean real = intelligence.balances().authoritative(e.network());
                if (real) {
                    realBalance = prefetchedBalance;
                    realBalance.ifPresent(b -> intelligence.balances().recordSenderBalanceBefore(f.eventId(), b));
                }
                IntelligencePipeline.Result r = intelligence.onFlow(f);
                fresh.addAll(r.newSignals());
                enriched = enrich(e, f, senderBefore, real, realBalance);
            }
        } else if (isFreeze(e)) {
            Optional<Address> target = address(e, "freeze.address");
            long known = target.map(this::distinctCounterparties).orElse(0L);
            fresh.addAll(intelligence.onEvent(e).newSignals());
            if (target.isPresent()) enriched = enrichFreeze(e, target.get(), known);
        } else if (e.type() == ChainEvent.EventType.APPROVAL) {
            fresh.addAll(intelligence.onEvent(e).newSignals());
            Optional<Address> owner = address(e, "approval.owner"), spender = address(e, "approval.spender");
            if (owner.isPresent() && spender.isPresent()) enriched = enrichApproval(e, owner.get(), spender.get());
        } else {
            fresh.addAll(intelligence.onEvent(e).newSignals());
        }
        history.record(enriched);
        evaluate(enriched);
        for (Signal s : fresh) {
            remember(s);
            ChainEvent se = signalEvent(s, e);
            history.record(se);
            evaluate(se);
        }
    }

    private void remember(Signal s) {
        synchronized (recentSignals) {
            recentSignals.addFirst(s);
            while (recentSignals.size() > MAX_SIGNALS_KEPT) recentSignals.removeLast();
        }
    }

    /**
     * Entry point for events produced inside the platform (INTERNAL_EVENT connector): recorded and evaluated by the
     * rule engine, which enforces the hop limit (loop protection). Serialised with ingestion.
     */
    public synchronized void publishInternal(ChainEvent e) {
        history.record(e);
        evaluate(e);
    }

    /**
     * Rule evaluation of one event: the alerts it creates or groups, their status changes and the outbox deliveries
     * are written atomically (one transaction in PostgreSQL mode, ADR-0006).
     * <p>Lock order: the {@link AlertService} monitor (its methods are {@code synchronized}) is taken BEFORE the
     * transaction opens. The transaction keeps alert row locks until commit while re-entering AlertService several
     * times; API calls and escalation take the monitor first and then row locks, so taking it here first keeps one
     * global order (monitor, then rows) and rules out a monitor/row-lock deadlock.
     */
    private void evaluate(ChainEvent e) {
        synchronized (alertService) {
            tx.run(() -> alerting.onEvent(e));
        }
    }

    /**
     * Freeze backfill sink: a historical issuer freeze/unfreeze (finalized block, never reorged) is applied to the label
     * and risk state exactly like a live one (ISSUER_FROZEN label, rescore, counterparty exposure). Idempotent: an event
     * id already applied, live or backfilled, is ignored, and so is a freeze older than the last one applied for that
     * address (IntelligencePipeline). Historical events are NOT evaluated by the rule engine (no alert flood for past
     * freezes); new signals are kept in {@link #recentSignals()}. Serialised with ingestion.
     */
    public synchronized void applyBackfilledFreeze(TokenFreeze z) {
        BlockRef ref = new BlockRef(z.network(), z.blockNumber(), z.blockHash(), null, z.timestamp());
        IntelligencePipeline.Result r = intelligence.onEvent(backfillMapper.mapFreeze(z, ref, clock.instant()));
        if (r.duplicate()) return;
        backfilledFreezesApplied.incrementAndGet();
        r.newSignals().forEach(this::remember);
    }

    /**
     * Address history sink (ADR-0020): historical transfers and approvals of one address, applied in chronological order
     * to the intelligence pipeline (flows, profiles, detection, scoring) exactly like live ones, but WITHOUT rule
     * evaluation: history never raises alerts. Idempotent on event ids (an event already indexed live is a no-op).
     * New signals are kept in {@link #recentSignals()}. Serialised with ingestion.
     */
    public synchronized void applyHistory(List<ChainEvent> events) {
        List<ChainEvent> ordered = new ArrayList<>(events);
        ordered.sort(Comparator.comparing((ChainEvent x) -> x.eventTime() == null ? Instant.EPOCH : x.eventTime())
                .thenComparingLong(ChainEvent::blockNumber).thenComparing(ChainEvent::eventId));
        for (ChainEvent e : ordered) {
            if (e.removed()) continue;
            IntelligencePipeline.Result r;
            if (e.type() == ChainEvent.EventType.TOKEN_TRANSFER || e.type() == ChainEvent.EventType.NATIVE_TRANSFER) {
                Optional<ValueFlow> f = toFlow(e);
                if (f.isEmpty()) continue;
                trackedWallets.add(f.get().from());
                trackedWallets.add(f.get().to());
                r = intelligence.onFlow(f.get());
            } else if (e.type() == ChainEvent.EventType.APPROVAL) {
                r = intelligence.onEvent(e);
            } else {
                continue;
            }
            if (r.duplicate()) continue;
            historyEventsApplied.incrementAndGet();
            r.newSignals().forEach(this::remember);
        }
    }

    /**
     * Balance of {@code token} held by {@code a} at the end of block {@code block - 1} (i.e. before every transfer of
     * {@code block}), from the network's provider; cached per (address, token, block) in a bounded LRU, so several
     * transfers of one block cost one call. Empty when the provider cannot tell or fails (counted).
     */
    /** Per-network budget of real balance lookups (see OciProperties.Chain); defaults apply when not configured. */
    public record BalanceBudget(int maxLookupsPerBlock, long maxLag) {
        public static final BalanceBudget DEFAULT = new BalanceBudget(5, 32);
    }

    private final Map<Network, BalanceBudget> balanceBudgets = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Network, long[]> balanceBudgetUse = new java.util.concurrent.ConcurrentHashMap<>(); // {block, used}
    private final AtomicLong balanceLookupsSkipped = new AtomicLong();

    public void setBalanceBudget(Network n, BalanceBudget b) { balanceBudgets.put(n, b); }

    /** Lookups not made because of the catch-up or per-block budget: wallet.balance was then absent. */
    public long balanceLookupsSkipped() { return balanceLookupsSkipped.get(); }

    /**
     * True when a real balance lookup may be made for this block: not while catching up far behind the head (a
     * synchronous RPC call per transfer would stall indexing and start-up), and at most N per block and network.
     */
    private boolean withinBalanceBudget(NetworkRuntime rt, long block) {
        BalanceBudget b = balanceBudgets.getOrDefault(rt.network(), BalanceBudget.DEFAULT);
        NetworkStatus st = rt.lastStatus();
        long head = st == null ? -1 : st.headBlock();
        if (head > 0 && head - block > b.maxLag()) return false;
        long[] use = balanceBudgetUse.computeIfAbsent(rt.network(), k -> new long[]{-1, 0});
        synchronized (use) {
            if (use[0] != block) { use[0] = block; use[1] = 0; }
            if (use[1] >= b.maxLookupsPerBlock()) return false;
            use[1]++;
            return true;
        }
    }

    /** One virtual thread per balance lookup: they only wait on the provider (Java 25 runtime: no pinning). */
    private final java.util.concurrent.ExecutorService lookups = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Fetches, concurrently, the real balances the block's transfers will ask for (see {@link #onEvent}), so the
     * sequential processing then finds them in the cache. Same selection as sequential processing: the first distinct
     * (sender, token) pairs of the block, within the per-block budget; nothing while catching up.
     */
    void prefetchBalances(List<ChainEvent> blockEvents) {
        if (blockEvents.isEmpty()) return;
        ChainEvent first = blockEvents.getFirst();
        NetworkRuntime rt = runtimes.get(first.network());
        if (rt == null || !intelligence.balances().authoritative(first.network())) return;
        BalanceBudget b = balanceBudgets.getOrDefault(rt.network(), BalanceBudget.DEFAULT);
        NetworkStatus st = rt.lastStatus();
        if (st != null && st.headBlock() > 0 && st.headBlock() - first.blockNumber() > b.maxLag()) return;
        java.util.LinkedHashMap<String, ValueFlow> wanted = new java.util.LinkedHashMap<>();
        for (ChainEvent e : blockEvents) {
            if (wanted.size() >= b.maxLookupsPerBlock()) break;
            if (e.removed() || (e.type() != ChainEvent.EventType.TOKEN_TRANSFER && e.type() != ChainEvent.EventType.NATIVE_TRANSFER)) continue;
            toFlow(e).ifPresent(f -> wanted.putIfAbsent(f.from().value() + "|" + ValueFlow.tokenKey(f.token()), f));
        }
        if (wanted.size() < 2) return; // nothing to parallelise: sequential processing does it
        List<java.util.concurrent.CompletableFuture<?>> all = new ArrayList<>();
        for (ValueFlow f : wanted.values()) {
            all.add(java.util.concurrent.CompletableFuture.runAsync(() -> realBalanceBefore(f.from(), f.token(), first.blockNumber()), lookups));
        }
        java.util.concurrent.CompletableFuture.allOf(all.toArray(java.util.concurrent.CompletableFuture[]::new)).join();
    }

    Optional<BigDecimal> realBalanceBefore(Address a, Token token, long block) {
        NetworkRuntime rt = runtimes.get(a.network());
        if (rt == null || block <= 0) return Optional.empty();
        long at = block - 1;
        String key = a.value() + "|" + ValueFlow.tokenKey(token) + "|" + at;
        Optional<BigDecimal> cached = balanceCache.get(key);
        if (cached != null) {
            balanceCacheHits.incrementAndGet();
            return cached;
        }
        if (!withinBalanceBudget(rt, block)) {
            balanceLookupsSkipped.incrementAndGet();
            return Optional.empty(); // not cached: a later event of the same block may fit in the budget
        }
        Optional<BigDecimal> v;
        try {
            balanceLookups.incrementAndGet();
            v = rt.provider().getBalanceAt(a, token, at).map(raw -> new BigDecimal(raw, token.decimals()));
            if (v.isEmpty()) balanceLookupFailures.incrementAndGet();
        } catch (RuntimeException ex) {
            balanceLookupFailures.incrementAndGet();
            log.debug("balance of {} {} at block {} unavailable: {}", a, token.symbol(), at, ex.getMessage());
            v = Optional.empty();
        }
        balanceCache.put(key, v);
        return v;
    }

    public long balanceLookups() { return balanceLookups.get(); }
    public long balanceCacheHits() { return balanceCacheHits.get(); }
    /** Lookups that gave no balance (provider error or unsupported): {@code wallet.balance} was then absent. */
    public long balanceLookupFailures() { return balanceLookupFailures.get(); }

    /** First block indexed live on a network (the address history covers the blocks before it). */
    public java.util.OptionalLong firstIndexedBlock(Network n) {
        Long b = firstIndexedBlock.get(n);
        return b == null ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(b);
    }

    public long historyEventsApplied() { return historyEventsApplied.get(); }

    public long backfilledFreezesApplied() { return backfilledFreezesApplied.get(); }
    /** Approval spender code lookups that failed (the field is then absent from the event). */
    public long spenderLookupFailures() { return spenderLookupFailures.get(); }

    public long eventsIndexed() { return eventsIndexed.get(); }

    private final Map<Network, Long> reorgCount = new ConcurrentHashMap<>();

    /** Chain reorganisations handled on this network since start (metrics). */
    public long reorgs(Network n) { return reorgCount.getOrDefault(n, 0L); }
    public long transfersIndexed() { return transfersIndexed.get(); }
    public long eventsRetracted() { return eventsRetracted.get(); }
    /** Addresses seen in at least one indexed value flow. */
    public Set<Address> trackedWallets() { return Set.copyOf(trackedWallets); }

    /** Most recent detection signals first. */
    public List<Signal> recentSignals() {
        synchronized (recentSignals) { return List.copyOf(recentSignals); }
    }

    private Optional<ValueFlow> toFlow(ChainEvent e) {
        try {
            Map<String, Object> f = e.fields();
            String hash = str(f.get("transaction.hash"));
            String from = str(f.get("transaction.from"));
            String to = str(f.get("transaction.to"));
            Object raw = f.get("transaction.amount_raw");
            if (hash == null || from == null || to == null || raw == null || e.eventTime() == null) return Optional.empty();
            BigInteger rawAmount = raw instanceof BigDecimal bd ? bd.toBigIntegerExact() : new BigInteger(raw.toString());
            String contract = str(f.get("transaction.token.contract"));
            String symbol = str(f.get("transaction.token.symbol"));
            Object dec = f.get("transaction.token.decimals");
            int decimals = dec instanceof Number n ? n.intValue() : 0;
            Token token = new Token(e.network(), contract, symbol == null ? "UNKNOWN" : symbol, decimals);
            return Optional.of(new ValueFlow(e.eventId(), e.network(), hash, e.eventTime(),
                    new Address(e.network(), from), new Address(e.network(), to), token, rawAmount));
        } catch (RuntimeException ex) {
            log.debug("event {} is not a value flow: {}", e.eventId(), ex.getMessage());
            return Optional.empty();
        }
    }

    private static boolean isFreeze(ChainEvent e) {
        return e.type() == ChainEvent.EventType.STABLECOIN_FREEZE || e.type() == ChainEvent.EventType.STABLECOIN_UNFREEZE;
    }

    private static Optional<Address> address(ChainEvent e, String field) {
        try {
            String v = str(e.field(field));
            return v == null || v.isBlank() ? Optional.empty() : Optional.of(new Address(e.network(), v));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private long distinctCounterparties(Address a) {
        return intelligence.flows().involving(a).stream().filter(f -> !f.from().equals(f.to())).map(f -> f.counterparty(a))
                .distinct().count();
    }

    /** Frozen address as {@code wallet.*} (risk after the freeze was applied) + number of known counterparties. */
    private ChainEvent enrichFreeze(ChainEvent e, Address target, long knownCounterparties) {
        Map<String, Object> fields = new LinkedHashMap<>(e.fields());
        fields.put("freeze.known_counterparties", knownCounterparties);
        fields.put("wallet.address", target.value());
        putRisk(fields, "wallet", target);
        putLabels(fields, "wallet", target);
        return new ChainEvent(e.eventId(), e.type(), e.network(), e.blockNumber(), e.blockHash(), e.eventTime(),
                e.ingestedAt(), e.removed(), fields);
    }

    /** Owner as {@code wallet.*}, spender as {@code counterparty.*}. */
    private ChainEvent enrichApproval(ChainEvent e, Address owner, Address spender) {
        Map<String, Object> fields = new LinkedHashMap<>(e.fields());
        fields.put("wallet.address", owner.value());
        putRisk(fields, "wallet", owner);
        putLabels(fields, "wallet", owner);
        fields.put("counterparty.address", spender.value());
        putRisk(fields, "counterparty", spender);
        putLabels(fields, "counterparty", spender);
        NetworkRuntime rt = runtimes.get(e.network());
        if (rt != null) {
            try {
                rt.provider().isContract(spender, e.blockNumber()).ifPresent(c -> fields.put("approval.spender_is_contract", c));
            } catch (RuntimeException ex) { // provider down: the field stays absent, indexing goes on
                spenderLookupFailures.incrementAndGet();
                log.debug("contract lookup of spender {} failed: {}", spender, ex.getMessage());
            }
        }
        Instant firstSeen = intelligence.profiles().profileOrEmpty(spender).firstActivity();
        if (firstSeen != null && observedLongEnough(e.network(), e.eventTime())) {
            fields.put("approval.spender_age_days", ageDays(firstSeen, e.eventTime()));
        }
        return new ChainEvent(e.eventId(), e.type(), e.network(), e.blockNumber(), e.blockHash(), e.eventTime(),
                e.ingestedAt(), e.removed(), fields);
    }

    /** Adds the wallet (sender) and counterparty (recipient) catalog fields. */
    private ChainEvent enrich(ChainEvent e, ValueFlow f, WalletProfile senderBefore, boolean real, Optional<BigDecimal> realBalance) {
        Map<String, Object> fields = new LinkedHashMap<>(e.fields());
        fields.put("wallet.address", f.from().value());
        if (real) {
            // real chain: the provider's balance at the end of the previous block, or NO field (balance conditions and
            // RATIO_OF_BALANCE then never match): an estimate from flows seen since the start would make every wallet
            // funded earlier look drained
            realBalance.ifPresent(b -> fields.put("wallet.balance", b));
        } else {
            TokenFlowStats stats = senderBefore.tokens().get(f.tokenKey());
            fields.put("wallet.balance", stats == null ? BigDecimal.ZERO : stats.balanceEstimate()); // demo ledger: exact
        }
        boolean aged = observedLongEnough(e.network(), e.eventTime());
        if (aged) {
            fields.put("wallet.tx_count", senderBefore.transactionCount());
            fields.put("wallet.age_days", ageDays(senderBefore.firstActivity(), e.eventTime()));
        }
        putRisk(fields, "wallet", f.from());
        putLabels(fields, "wallet", f.from());
        fields.put("counterparty.address", f.to().value());
        putRisk(fields, "counterparty", f.to());
        putLabels(fields, "counterparty", f.to());
        WalletProfile cp = intelligence.profiles().profileOrEmpty(f.to());
        if (aged) fields.put("counterparty.age_days", ageDays(cp.firstActivity(), e.eventTime()));
        return new ChainEvent(e.eventId(), e.type(), e.network(), e.blockNumber(), e.blockHash(), e.eventTime(),
                e.ingestedAt(), e.removed(), fields);
    }

    private void putRisk(Map<String, Object> fields, String prefix, Address a) {
        Optional<RiskScore> s = intelligence.scoring().latest(a);
        fields.put(prefix + ".risk_score", (long) s.map(RiskScore::score).orElse(0));
        if (prefix.equals("wallet")) fields.put("wallet.risk_level", s.map(x -> x.level().name()).orElse("UNKNOWN"));
    }

    private void putLabels(Map<String, Object> fields, String prefix, Address a) {
        List<AddressLabel> ls = labels.findByAddress(a);
        if (!ls.isEmpty()) {
            fields.put(prefix + ".labels", ls.stream().map(l -> categoryId(l.category())).distinct().collect(Collectors.joining(",")));
        }
        boolean sanctioned = ls.stream().anyMatch(l -> l.category() == LabelCategory.SANCTIONED
                && l.evidenceLevel() == Signal.EvidenceLevel.CONFIRMED_BY_SOURCE);
        fields.put(prefix + ".sanctioned", sanctioned);
    }

    private static long ageDays(Instant first, Instant at) {
        if (first == null || at == null || at.isBefore(first)) return 0L;
        return Duration.between(first, at).toDays();
    }

    private ChainEvent signalEvent(Signal s, ChainEvent cause) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("signal.type", s.type().name());
        f.put("signal.severity", s.severity().name());
        f.put("signal.confidence", BigDecimal.valueOf(s.confidence()));
        f.put("signal.evidence_level", s.evidenceLevel().name());
        f.put("wallet.address", s.address().value());
        if (s.txHash() != null) f.put("transaction.hash", s.txHash());
        if (s.title() != null) f.put("signal.title", s.title());
        if (s.summary() != null) f.put("signal.summary", s.summary());
        return new ChainEvent("signal:" + s.id(), ChainEvent.EventType.DETECTION_SIGNAL, cause.network(), cause.blockNumber(),
                cause.blockHash(), cause.eventTime(), clock.instant(), false, f);
    }

    /** API form of a label category: lowercase, dashes ({@code STOLEN_FUNDS -> stolen-funds}). */
    public static String categoryId(LabelCategory c) { return c.name().toLowerCase(Locale.ROOT).replace('_', '-'); }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
