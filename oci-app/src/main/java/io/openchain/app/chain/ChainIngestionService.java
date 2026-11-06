package io.openchain.app.chain;

import io.openchain.alerting.alert.AlertService;
import io.openchain.alerting.pipeline.AlertingPipeline;
import io.openchain.app.labels.LabelCatalog;
import io.openchain.app.persistence.TransactionRunner;
import io.openchain.blockchain.indexer.BlockIndexer;
import io.openchain.blockchain.indexer.InMemoryCheckpointStore;
import io.openchain.blockchain.indexer.IndexerConfig;
import io.openchain.blockchain.simulated.SimulatedChainProvider;
import io.openchain.blockchain.token.TokenRegistry;
import io.openchain.core.detection.Signal;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.Address;
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
 * Each indexed transfer is enriched with the {@code wallet.*}/{@code counterparty.*} catalog fields computed by the
 * intelligence module before the rule engine sees it; every new detection signal is also forwarded to the rule
 * engine as a {@code DETECTION_SIGNAL} event. Ingestion is serialised (one poll at a time).
 * TODO(outbox): publish events through the outbox / Kafka instead of direct calls (ADR-0006).
 */
@Service
public class ChainIngestionService {

    private static final Logger log = LoggerFactory.getLogger(ChainIngestionService.class);
    private static final int MAX_SIGNALS_KEPT = 10_000;
    private static final int MAX_POLLS_PER_TICK = 1_000;

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
    private final Set<Address> trackedWallets = ConcurrentHashMap.newKeySet();

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
    }

    // ---- registration -----------------------------------------------------------------------------------------

    public synchronized NetworkRuntime register(BlockchainProvider provider, SimulatedChainProvider simulator,
                                                DemoLedgerProvider ledger, IndexerConfig config, Duration blockTime) {
        Network n = provider.network();
        if (runtimes.containsKey(n)) throw new IllegalStateException("network already registered: " + n.id());
        // TODO(persistence): PostgreSQL checkpoint store; in memory, indexing restarts from the configured start block
        // (alert creation is idempotent on replayed events, see AlertService#onMatch)
        BlockIndexer indexer = new BlockIndexer(provider, new InMemoryCheckpointStore(), this::onEvent, config, tokens, clock);
        NetworkRuntime rt = new NetworkRuntime(n, provider, simulator, ledger, indexer, blockTime);
        rt.indexerConfig = config;
        runtimes.put(n, rt);
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
    public synchronized void poll(NetworkRuntime rt) {
        rt.lastPollAt = clock.instant();
        try {
            for (int i = 0; i < MAX_POLLS_PER_TICK; i++) {
                BlockIndexer.PollResult r = rt.indexer.poll();
                rt.lastIndexedBlock = r.lastBlock();
                if (r.blocksIndexed() == 0) break;
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

    /** Indexer sink. Package-visible for tests; called under the {@link #poll} lock. */
    void onEvent(ChainEvent e) {
        if (e.removed()) eventsRetracted.incrementAndGet(); else eventsIndexed.incrementAndGet();
        if (e.removed()) {
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
                IntelligencePipeline.Result r = intelligence.onFlow(f);
                fresh.addAll(r.newSignals());
                enriched = enrich(e, f, senderBefore);
            }
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

    public long eventsIndexed() { return eventsIndexed.get(); }
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

    /** Adds the wallet (sender) and counterparty (recipient) catalog fields. */
    private ChainEvent enrich(ChainEvent e, ValueFlow f, WalletProfile senderBefore) {
        Map<String, Object> fields = new LinkedHashMap<>(e.fields());
        fields.put("wallet.address", f.from().value());
        TokenFlowStats stats = senderBefore.tokens().get(f.tokenKey());
        fields.put("wallet.balance", stats == null ? BigDecimal.ZERO : stats.balanceEstimate());
        fields.put("wallet.tx_count", senderBefore.transactionCount());
        fields.put("wallet.age_days", ageDays(senderBefore.firstActivity(), e.eventTime()));
        putRisk(fields, "wallet", f.from());
        putLabels(fields, "wallet", f.from());
        fields.put("counterparty.address", f.to().value());
        putRisk(fields, "counterparty", f.to());
        putLabels(fields, "counterparty", f.to());
        WalletProfile cp = intelligence.profiles().profileOrEmpty(f.to());
        fields.put("counterparty.age_days", ageDays(cp.firstActivity(), e.eventTime()));
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
        return new ChainEvent("signal:" + s.id(), ChainEvent.EventType.DETECTION_SIGNAL, cause.network(), cause.blockNumber(),
                cause.blockHash(), cause.eventTime(), clock.instant(), false, f);
    }

    /** API form of a label category: lowercase, dashes ({@code STOLEN_FUNDS -> stolen-funds}). */
    public static String categoryId(LabelCategory c) { return c.name().toLowerCase(Locale.ROOT).replace('_', '-'); }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
