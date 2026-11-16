package io.openchain.app.config;

import io.openchain.alerting.alert.AlertService;
import io.openchain.alerting.notify.ConnectorRegistry;
import io.openchain.alerting.pipeline.AlertingPipeline;
import io.openchain.app.chain.ChainIngestionService;
import io.openchain.app.chain.DemoLedgerProvider;
import io.openchain.app.chain.FreezeBackfillService;
import io.openchain.blockchain.backfill.FreezeBackfill;
import io.openchain.blockchain.backfill.FreezeLogScanner;
import io.openchain.blockchain.token.StablecoinFreezeSources;
import io.openchain.app.chain.TimeShiftedProvider;
import io.openchain.app.demo.DemoNotificationSink;
import io.openchain.app.demo.DemoScenarios;
import io.openchain.alerting.notify.connector.InternalEventConnector;
import io.openchain.blockchain.tron.TronAddress;
import org.springframework.beans.factory.ObjectProvider;
import io.openchain.app.labels.LabelCatalog;
import io.openchain.app.rules.RuleRegistry;
import io.openchain.app.security.ApiKeyService;
import io.openchain.app.security.UserDirectory;
import io.openchain.blockchain.evm.EvmConfig;
import io.openchain.blockchain.evm.EvmJsonRpcProvider;
import io.openchain.blockchain.indexer.IndexerConfig;
import io.openchain.blockchain.simulated.SimulatedChainProvider;
import io.openchain.blockchain.token.TokenRegistry;
import io.openchain.blockchain.transport.HttpJsonTransport;
import io.openchain.blockchain.transport.JsonRpcClient;
import io.openchain.blockchain.transport.RpcEndpoint;
import io.openchain.blockchain.tron.TronConfig;
import io.openchain.blockchain.tron.TronGridProvider;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.NetworkStatus;
import io.openchain.core.provider.BlockchainProvider;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.LabelCategory;
import io.openchain.intelligence.label.LabelFileLoader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Startup: label files, rules from the repository {@code rules/} directory, chains (simulated in demo mode, real
 * providers only when configured), demo API key, and the background loops (indexing, outbox dispatch, escalation).
 */
@Component
public class Bootstrap implements ApplicationRunner, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

    private final OciProperties props;
    private final LabelCatalog labels;
    private final RuleRegistry rules;
    private final ChainIngestionService chains;
    private final AlertingPipeline alerting;
    private final AlertService alertService;
    private final ConnectorRegistry connectors;
    private final ApiKeyService keys;
    private final UserDirectory users;
    private final TokenRegistry tokens;
    private final Clock clock;
    private final DemoNotificationSink demoSink;
    private final DemoScenarios demoScenarios;
    private final FreezeBackfillService backfills;
    private final io.openchain.app.history.AddressHistoryService history;
    /** Working-set snapshot (ADR-0024 step 3d); null when disabled. Used only when {@link #snapshotsActive}. */
    private final io.openchain.app.persistence.WorkingSetSnapshotService snapshots;
    private volatile boolean snapshotsActive;
    /** Delay before retrying a real chain whose provider was unreachable at start-up. */
    private static final Duration START_RETRY = Duration.ofSeconds(30);
    // virtual threads: polls and lookups mostly wait on providers; one task per network runs in parallel
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(8,
            Thread.ofVirtual().name("oci-background-", 0).factory());

    public Bootstrap(OciProperties props, LabelCatalog labels, RuleRegistry rules, ChainIngestionService chains,
                     AlertingPipeline alerting, AlertService alertService, ConnectorRegistry connectors, ApiKeyService keys,
                     UserDirectory users, TokenRegistry tokens, Clock clock, ObjectProvider<DemoNotificationSink> demoSink,
                     ObjectProvider<DemoScenarios> demoScenarios, FreezeBackfillService backfills,
                     io.openchain.app.history.AddressHistoryService history,
                     ObjectProvider<io.openchain.app.persistence.WorkingSetSnapshotService> snapshots) {
        this.snapshots = snapshots.getIfAvailable();
        this.backfills = backfills;
        this.history = history;
        this.demoSink = demoSink.getIfAvailable();
        this.demoScenarios = demoScenarios.getIfAvailable();
        this.props = props;
        this.labels = labels;
        this.rules = rules;
        this.chains = chains;
        this.alerting = alerting;
        this.alertService = alertService;
        this.connectors = connectors;
        this.keys = keys;
        this.users = users;
        this.tokens = tokens;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        long t0 = System.nanoTime();
        loadLabelFiles();
        boolean demo = props.getDemo().isEnabled();
        refuseRealChainsInDemoMode(demo);
        chains.setAgeWarmup(props.getIngestion().getAgeWarmup());
        chains.setSeenAddressesBits(props.getIngestion().getSeenAddressesBits());
        // internal bus: INTERNAL_EVENT actions feed derived events back to the rule engine (hop-limited by the engine)
        connectors.register(new InternalEventConnector("internal-bus", chains::publishInternal, clock));
        if (demo) {
            demoKeys();
            if (demoSink != null) connectors.register(demoSink);
        } else {
            // a database used for a demo earlier must not keep demo credentials (fixed demo-ui key, generated admin key)
            int retired = keys.retireGenerated("demo-admin", "demo-bootstrap", "bootstrap")
                    + keys.retireGenerated("demo-ui", "demo-bootstrap", "bootstrap");
            if (retired > 0) log.warn("demo mode is off: {} key(s) created by a previous demo bootstrap revoked", retired);
        }
        if (props.getRules().isLoadOnStartup()) loadRules();
        rules.disableUnreachable();
        warnAboutNotificationRouting(demo);
        if (demo) for (String id : props.getDemo().getNetworks()) startSimulated(Network.fromId(id));
        restoreWorkingSet(demo); // before any real indexer starts: they resume where the snapshot was taken
        props.getChains().forEach(this::startRealChain);
        // stored address histories are re-applied in the background: with a few dozen of them this takes minutes
        // (each event goes through the intelligence pipeline, serialised with live ingestion) and must not hold readiness
        scheduler.execute(guard("history restore", () -> {
            // journaled flows, signals and scores first (ADR-0024 step 2), then address histories. A failed journal
            // reload must not prevent the stored histories from being re-applied (code review I23).
            try {
                restoreIntelligence();
            } catch (RuntimeException e) {
                log.error("intelligence journal not restored, continuing with address histories: {}", e.toString());
            }
            int restored = history.restore();
            if (restored > 0) log.info("address history: {} stored backfill(s) re-applied", restored);
        }));

        Duration dispatch = props.getIngestion().getDispatchInterval();
        scheduler.scheduleWithFixedDelay(guard("dispatch", () -> alerting.dispatch(200)), dispatch.toMillis(), dispatch.toMillis(), TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(guard("escalation", alertService::escalateOverdue), 60, 60, TimeUnit.SECONDS);
        // events whose rule evaluation failed (transaction error) are retried even when no new event arrives (review I5)
        scheduler.scheduleWithFixedDelay(guard("evaluation retry", chains::retryPendingEvaluations), 30, 30, TimeUnit.SECONDS);
        var ing = props.getIngestion();
        long trim = ing.getWorkingSetTrimInterval().toMillis();
        if (trim > 0) {
            scheduler.scheduleWithFixedDelay(guard("working-set trim", this::trimWorkingSet), trim, trim, TimeUnit.MILLISECONDS);
            log.info("working-set trim every {}: flows older than {}, profiles idle for {}, signals older than {} once their wallet left "
                    + "the working set (HIGH/CRITICAL, watchlist and loaded histories kept); caps {} flows, {} signals, {} profiles",
                    ing.getWorkingSetTrimInterval(), ing.getWorkingSetFlowRetention(), ing.getWorkingSetProfileIdle(),
                    ing.getWorkingSetSignalRetention(), ing.getWorkingSetMaxFlows(), ing.getWorkingSetMaxSignals(), ing.getWorkingSetMaxProfiles());
        } else {
            log.warn("working-set trim disabled (OCI_INGESTION_WORKING_SET_TRIM_INTERVAL={}): memory grows with uptime",
                    ing.getWorkingSetTrimInterval());
        }
        // first flush of pending deliveries off the main thread: INTERNAL_EVENT deliveries wait for the ingestion lock, so a
        // long first indexing pass on a busy real chain must not keep start-up (and readiness) waiting
        scheduler.execute(guard("initial dispatch", () -> alerting.dispatch(1_000)));
        log.info("OpenChain Intelligence ready in {} ms: {} network(s), {} rule(s), {} label(s), {} alert(s)",
                (System.nanoTime() - t0) / 1_000_000, chains.runtimes().size(), rules.list().size(), labels.size(),
                alertService.repository().findAll().size());
    }

    // ---- labels / rules -----------------------------------------------------------------------------------------

    private void loadLabelFiles() {
        for (String f : props.getLabels().getFiles()) {
            try {
                List<AddressLabel> ls;
                if (f.startsWith("classpath:")) {
                    ls = LabelFileLoader.fromClasspath(f.substring("classpath:".length()));
                } else if (f.endsWith(".json")) {
                    try (var in = Files.newInputStream(Path.of(f))) { ls = LabelFileLoader.readJson(in); }
                } else {
                    try (Reader r = new InputStreamReader(Files.newInputStream(Path.of(f)), StandardCharsets.UTF_8)) { ls = LabelFileLoader.readCsv(r); }
                }
                labels.saveAll(ls);
                log.info("loaded {} label(s) from {}", ls.size(), f);
            } catch (IOException | RuntimeException e) {
                log.error("label file {} rejected: {}", f, e.getMessage());
            }
        }
    }

    /** Says at start-up when enabled rules cannot notify anyone (instead of thousands of silent dead letters). */
    private void warnAboutNotificationRouting(boolean demo) {
        String def = props.getNotifications().getDefaultConnector();
        if (def != null && !def.isBlank() && connectors.find(def.strip()).isEmpty()) {
            log.warn("OCI_DEFAULT_CONNECTOR '{}' does not exist: alerts routed to the rule pack placeholder will not be notified", def.strip());
        }
        if (demo || (def != null && !def.isBlank())) return;
        List<String> silent = rules.list().stream().filter(RuleRegistry.StoredRule::enabled)
                .filter(r -> r.current().rule().actions().stream()
                        .anyMatch(a -> io.openchain.alerting.notify.delivery.DeliveryService.PLACEHOLDERS.contains(a.connectorId())
                                && connectors.find(a.connectorId()).isEmpty()))
                .map(RuleRegistry.StoredRule::id).sorted().toList();
        if (!silent.isEmpty()) {
            log.warn("{} enabled rule(s) route to the placeholder 'default-webhook' and no OCI_DEFAULT_CONNECTOR is set: their "
                    + "alerts are recorded but not notified ({})", silent.size(), String.join(", ", silent));
        }
    }

    private void loadRules() {
        for (String d : props.getRules().getDirectories()) {
            Path p = Path.of(d);
            if (Files.isDirectory(p)) {
                rules.loadDirectory(p);
                return;
            }
        }
        log.warn("no rules directory found among {}", props.getRules().getDirectories());
    }

    // ---- demo ---------------------------------------------------------------------------------------------------

    private void demoKeys() {
        OciProperties.Demo d = props.getDemo();
        String configured = d.getApiKey();
        if (configured != null && !configured.isBlank()) {
            keys.register(configured.trim(), "demo-admin", List.of("*"), null, "demo-bootstrap");
            log.warn("DEMO MODE: demo admin API key configured ({})", ApiKeyService.redact(configured.trim()));
        } else {
            // with a persistent store, the key generated at the previous start is revoked (and older ones purged)
            int retired = keys.retireGenerated("demo-admin", "demo-bootstrap", "demo-bootstrap");
            if (retired > 0) log.info("DEMO MODE: {} demo admin key(s) generated at a previous start revoked", retired);
            ApiKeyService.Created c = keys.create("demo-admin", List.of("*"), Duration.ofDays(1), "demo-bootstrap");
            // Printed once, on purpose, and only in demo mode. Never enable demo mode in production.
            log.warn("DEMO MODE: demo admin API key (all permissions, valid 24h, shown once): {}", c.plaintext());
        }
        if (d.getUiKey() != null && !d.getUiKey().isBlank()) {
            keys.register(d.getUiKey().trim(), "demo-ui", d.getUiKeyScopes(), null, "demo-bootstrap");
            log.warn("DEMO MODE: fixed Angular dev-server key enabled ({}, permissions {}) - publicly documented, demo only",
                    ApiKeyService.redact(d.getUiKey().trim()), d.getUiKeyScopes());
        }
        Instant now = clock.instant();
        // insert-if-absent: role changes made by an administrator survive a restart with a persistent store
        users.putIfAbsent(new UserDirectory.User("u1", "admin@example.invalid", "Demo Admin", "ADMIN", now.minus(Duration.ofHours(1))));
        users.putIfAbsent(new UserDirectory.User("u2", "analyst@example.invalid", "Demo Analyst", "ANALYST", now.minus(Duration.ofHours(3))));
        users.putIfAbsent(new UserDirectory.User("u3", "viewer@example.invalid", "Demo Viewer", "VIEWER", null));
    }

    private void startSimulated(Network n) {
        OciProperties.Demo d = props.getDemo();
        Duration blockTime = n == Network.TRON ? Duration.ofSeconds(3) : Duration.ofSeconds(12); // as SimulatedChainProvider
        SimulatedChainProvider sim = new SimulatedChainProvider("sim-" + n.id(), n, d.getSeed() + n.ordinal(),
                d.getMaxTransfersPerBlock(), d.getAddressPoolSize(), tokens);
        int blocks = (int) Math.max(1, Duration.ofMinutes(d.getSeedMinutes()).toSeconds() / blockTime.toSeconds());
        sim.mine(blocks);
        DemoLedgerProvider ledger = new DemoLedgerProvider(sim, faucetOf(n), 500_000);
        Address sanctioned = seedDemoLabels(sim, ledger.faucet());
        if (demoScenarios != null && n == Network.TRON) demoScenarios.plan(ledger, sim.head(), sim.addressPool(), sanctioned, labels);
        Instant headTime = sim.getBlock(sim.head()).orElseThrow().timestamp();
        TimeShiftedProvider provider = new TimeShiftedProvider(ledger, Duration.between(headTime, clock.instant()));
        int confirmations = Math.min(n.defaultConfirmations(), 12);
        IndexerConfig cfg = new IndexerConfig("demo-" + n.id(), 1, confirmations, n.maxReorgDepth(), 200);
        ChainIngestionService.NetworkRuntime rt = chains.register(provider, sim, ledger, cfg, blockTime);
        history.markSimulated(n);
        long t0 = System.nanoTime();
        chains.poll(rt);
        log.info("demo network {}: {} simulated blocks indexed in {} ms ({} overdrafting simulated transfers dropped)", n.id(),
                rt.lastIndexedBlock(), (System.nanoTime() - t0) / 1_000_000, ledger.droppedOverdrafts());
        if (d.isLive()) {
            scheduler.scheduleAtFixedRate(guard("mine " + n.id(), () -> { sim.mine(1); chains.poll(rt); }),
                    blockTime.toMillis(), blockTime.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** Fictional funding source of the demo ledger. */
    static Address faucetOf(Network n) {
        String hex = java.util.HexFormat.of().formatHex(sha256("oci-demo-faucet:" + n.id())).substring(0, 40);
        return switch (n) {
            case TRON -> new Address(n, TronAddress.fromHex(hex));
            case ETHEREUM, BSC, POLYGON -> new Address(n, "0x" + hex);
            default -> new Address(n, "sim" + hex);
        };
    }

    private static byte[] sha256(String s) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Fictional labels on simulated addresses; returns the demo-sanctioned address. */
    private Address seedDemoLabels(SimulatedChainProvider sim, Address faucet) {
        List<Address> pool = sim.addressPool();
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        record L(LabelCategory c, String name, String source, double rel, EvidenceLevel ev, String ref) {}
        // the demo-sanctioned entity is a dedicated address outside the random pool: random simulated traffic would
        // otherwise give most wallets sanctions exposure (only scenario B touches it)
        String hex = java.util.HexFormat.of().formatHex(sha256("oci-demo-sanctioned:" + sim.network().id())).substring(0, 40);
        Address sanctioned = sim.network() == Network.TRON ? new Address(sim.network(), TronAddress.fromHex(hex))
                : new Address(sim.network(), (sim.network() == Network.ETHEREUM || sim.network() == Network.BSC || sim.network() == Network.POLYGON ? "0x" : "sim") + hex);
        labels.save(new AddressLabel(sanctioned, LabelCategory.SANCTIONED, "DEMO fictional sanctioned entity", "demo-sanctions-list", 1.0, at,
                EvidenceLevel.CONFIRMED_BY_SOURCE, "demo://sanctions/sim"));
        List<L> specs = List.of(
                new L(LabelCategory.MIXER, "DEMO fictional mixer", "demo-community-feed", 0.6, EvidenceLevel.RISK_SIGNAL, "demo://feed/mixer"),
                new L(LabelCategory.SCAM, "DEMO fictional scam report", "demo-community-feed", 0.4, EvidenceLevel.RISK_SIGNAL, "demo://feed/scam"),
                new L(LabelCategory.EXCHANGE, "DEMO fictional exchange hot wallet", "demo-directory", 0.9, EvidenceLevel.ANOMALY, null),
                new L(LabelCategory.BRIDGE, "DEMO fictional bridge", "demo-directory", 0.9, EvidenceLevel.ANOMALY, null));
        for (int i = 0; i < specs.size() && i < pool.size(); i++) {
            L s = specs.get(i);
            labels.save(new AddressLabel(pool.get(pool.size() - 1 - i), s.c(), s.name(), s.source(), s.rel(), at, s.ev(), s.ref()));
        }
        labels.save(new AddressLabel(faucet, LabelCategory.EXCHANGE, "DEMO fictional exchange faucet (funds the simulated wallets)",
                "demo-directory", 0.9, at, EvidenceLevel.ANOMALY, null));
        return sanctioned;
    }

    // ---- real providers -----------------------------------------------------------------------------------------

    /**
     * Polls a real chain on its own (virtual) thread: again at once while it is behind the head, after the poll
     * interval once caught up. Rescheduled in a finally block, so an error never stops indexing.
     */
    private void schedulePolling(String id, ChainIngestionService.NetworkRuntime rt, long everyMillis, long delayMillis) {
        scheduler.schedule(() -> {
            boolean more = false;
            try {
                more = chains.poll(rt);
            } catch (RuntimeException e) {
                log.error("poll {} failed: {}", id, e.toString());
            } catch (Throwable e) { // never lose the polling loop on an Error; rescheduled below
                log.error("poll {} failed with an error: {}", id, e.toString(), e);
            } finally {
                if (!scheduler.isShutdown()) schedulePolling(id, rt, everyMillis, more ? 0 : everyMillis);
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Demo mode runs with publicly known development secrets (API key pepper, master key) and generated keys: real chain
     * data must never be processed under it. Refuses to start instead of silently mixing them.
     */
    void refuseRealChainsInDemoMode(boolean demo) {
        if (!demo) return;
        List<String> real = props.getChains().entrySet().stream().filter(e -> e.getValue().isEnabled()).map(Map.Entry::getKey).sorted().toList();
        if (!real.isEmpty()) {
            throw new IllegalStateException("real chain(s) " + real + " enabled while OCI_DEMO_MODE=true: demo mode uses public "
                    + "development secrets. Set OCI_DEMO_MODE=false (with OCI_SECURITY_API_KEY_PEPPER and OCI_CRYPTO_MASTER_KEY) "
                    + "or disable the real chains.");
        }
    }

    private io.openchain.app.watchlist.WatchlistService watchlistService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setWatchlistService(io.openchain.app.watchlist.WatchlistService w) { this.watchlistService = w; }

    private io.openchain.app.chain.ExemptWorkingSetMonitor exemptMonitor;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setExemptMonitor(io.openchain.app.chain.ExemptWorkingSetMonitor m) { this.exemptMonitor = m; }

    /** Bounds the in-memory working set; watched wallets and addresses with loaded history are kept. */
    private void trimWorkingSet() {
        var ing = props.getIngestion();
        java.util.Set<Address> histories = history.historyAddresses();
        var w = watchlistService;
        var r = chains.trimWorkingSet(ing.getWorkingSetFlowRetention(), ing.getWorkingSetSignalRetention(), ing.getWorkingSetProfileIdle(),
                ing.getWorkingSetMaxFlows(), ing.getWorkingSetMaxSignals(), ing.getWorkingSetMaxProfiles(),
                a -> histories.contains(a) || (w != null && w.isWatched(a)));
        log.info("working set trimmed in {} ms: removed {} flow(s), {} signal(s) {}, {} idle profile(s); kept {} flow(s), {} signal(s), "
                + "{} profile(s), {} scored address(es), {} tracked wallet(s)", r.millis(), r.flows(), r.signals(), r.signalsRemovedBySeverity(),
                r.profiles(), r.remainingFlows(), r.remainingSignals(), r.remainingProfiles(), r.remainingScoredAddresses(), r.trackedWallets());
        if (exemptMonitor != null) { // observation only: measures the exempt addresses, changes nothing
            var x = exemptMonitor.refresh();
            log.info("exempt working set: {} address(es), {} distinct flow(s) ({} outside the watchlist), measured in {} ms; by category {}; "
                    + "over the proposed caps (not enforced): {} address(es) above {} flows, {} flow(s) above {} in all",
                    x.entries().size(), x.distinctExemptFlows(), x.distinctExemptFlowsOutsideWatchlist(), x.millis(),
                    x.byCategory().entrySet().stream().filter(e -> e.getValue().addresses() > 0)
                            .map(e -> e.getKey() + "=" + e.getValue().addresses() + "/" + e.getValue().flows() + "f").toList(),
                    x.addressesOverProposedPerAddressCap(), io.openchain.app.chain.ExemptWorkingSetMonitor.PROPOSED_FLOWS_PER_ADDRESS,
                    x.overProposedTotal(), io.openchain.app.chain.ExemptWorkingSetMonitor.PROPOSED_EXEMPT_FLOWS_TOTAL);
        }
    }

    private io.openchain.app.journal.JdbcIntelligenceJournal journal;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setJournal(io.openchain.app.journal.JdbcIntelligenceJournal journal) { this.journal = journal; }

    /** Reloads the journaled intelligence within the retention period and schedules the daily purge of older rows. */
    private void restoreIntelligence() {
        if (journal == null) return;
        Duration retention = props.getPersistence().getIntelligenceRetention();
        Instant since = clock.instant().minus(retention);
        // scheduled before the reload: a failed reload must not leave the journal growing without purge
        scheduler.scheduleWithFixedDelay(guard("journal purge", () -> {
            int n = journal.purge(clock.instant().minus(retention));
            if (n > 0) log.info("intelligence journal: {} row(s) older than {} purged", n, retention);
        }), 1, 24, TimeUnit.HOURS);
        long t0 = System.nanoTime();
        List<io.openchain.intelligence.flow.ValueFlow> flows = new java.util.ArrayList<>();
        journal.loadFlows(since, 10_000, flows::addAll);
        var signals = journal.loadSignals(since);
        var scores = journal.loadScores(since);
        chains.restoreIntelligence(flows, signals, scores);
        log.info("intelligence restored from PostgreSQL in {} ms: {} flow(s), {} signal(s), {} score(s) since {}",
                (System.nanoTime() - t0) / 1_000_000, flows.size(), signals.size(), scores.size(), since);
    }

    /**
     * Resumes from the durable checkpoint, unless it is more than max-resume-gap blocks behind the start position computed
     * from the head: then indexing restarts there and the skipped range is logged (never silently).
     */
    private void guardResumeGap(Network n, OciProperties.Chain c, long start) {
        String indexerId = n.id() + "-indexer";
        var store = chains.realCheckpoints();
        var saved = store.load(indexerId);
        // coverage metadata (Fund Flow Trace P0-B): the skipped range is recorded, not only logged
        io.openchain.app.chain.IndexingCoverage.onStart(chains.indexingCoverage(), n, saved.map(x -> x.lastBlock()).orElse(null),
                start, c.getMaxResumeGap(), clock.instant());
        if (saved.isEmpty()) return;
        long last = saved.get().lastBlock();
        if (c.getMaxResumeGap() > 0 && start - last > c.getMaxResumeGap()) {
            log.warn("chain {}: checkpoint at block {} is {} blocks behind; resuming at block {} (blocks {}..{} are NOT indexed, "
                    + "max-resume-gap {}; recorded in indexer_gaps)", n.id(), last, start - last, start, last + 1, start - 1, c.getMaxResumeGap());
            store.save(indexerId, new io.openchain.blockchain.indexer.Checkpoint(n, start - 1, List.of()));
        } else {
            log.info("chain {}: resuming after block {} (checkpoint; the start position computed from the head is {})", n.id(), last, start);
        }
    }

    private void startRealChain(String id, OciProperties.Chain c) {
        if (!c.isEnabled()) return;
        try {
            Network n = Network.fromId(id);
            if (chains.runtime(n).isPresent()) {
                log.warn("chain {} is already simulated (demo mode); real provider ignored", id);
                return;
            }
            // two clients on the same endpoints: live indexing, and background work (address histories, freeze backfill),
            // each with its own circuit breaker and rate limiter, so that a history hammering the provider can no longer
            // open the breaker that live indexing depends on (production 2026-11-16, TRON stalled ~80 min). The rates add
            // up to the previous single budget, so the provider sees no more requests than before.
            HttpJsonTransport http = transport(n, c, "", LIVE_RESILIENCE);
            HttpJsonTransport background = transport(n, c, "-background", BACKGROUND_RESILIENCE);
            if (!c.getFallbackUrls().isEmpty()) log.info("chain {}: {} fallback endpoint(s) configured", id, c.getFallbackUrls().stream().filter(u -> u != null && !u.isBlank()).count());
            BlockchainProvider p = provider(n, c, http, "");
            BlockchainProvider bg = provider(n, c, background, "-background");
            long start = c.getStartBlock();
            if (start < 0) {
                // relative start: needs the real head. An unreachable provider reports an unhealthy status with head 0
                // (it does not throw): starting from "0 - 100" would index from genesis, so retry later instead.
                NetworkStatus st = p.getNetworkStatus();
                if (st == null || !st.healthy() || st.headBlock() <= 0) {
                    log.warn("chain {}: head block unknown ({}); retrying in {} s", id, st == null ? "no status" : st.detail(),
                            START_RETRY.toSeconds());
                    scheduler.schedule(guard("start " + id, () -> startRealChain(id, c)), START_RETRY.toMillis(), TimeUnit.MILLISECONDS);
                    return;
                }
                start = Math.max(0, st.headBlock() + c.getStartBlock());
            }
            guardResumeGap(n, c, start);
            IndexerConfig cfg = IndexerConfig.of(n.id() + "-indexer", n, start).withApprovalsOfUnknownTokens(c.isApprovalsOfUnknownTokens())
                    .withWatchedContracts(n, java.util.Set.copyOf(c.getWatchedContracts()))
                    .withAdminChangesOfAllContracts(c.isAdminChangesOfAllContracts())
                    .withMaxBlocksPerPoll(c.getMaxBlocksPerPoll());
            ChainIngestionService.NetworkRuntime rt = chains.register(p, null, null, cfg, null);
            chains.setBalanceBudget(n, new ChainIngestionService.BalanceBudget(c.getRealBalanceMaxLookupsPerBlock(), c.getRealBalanceMaxLag()));
            startFreezeBackfill(n, bg, c.getFreezeBackfill());
            OciProperties.History h = c.getHistory();
            if (h.isEnabled() && bg instanceof io.openchain.core.history.AddressHistorySource src) {
                history.register(src, new io.openchain.app.history.AddressHistoryService.Config(h.getMaxBlocks(), h.getMaxPages(),
                        h.getMaxEvents(), h.getPause()));
            }
            long every = props.getIngestion().getPollInterval().toMillis();
            schedulePolling(n.id(), rt, every, 0);
        } catch (RuntimeException e) {
            log.error("chain {} not started: {}", id, e.getMessage());
        }
    }

    /** Live indexing: 8 requests/s (burst 16); with {@link #BACKGROUND_RESILIENCE}, the previous shared budget of 10/s. */
    static final io.openchain.blockchain.transport.ResilienceConfig LIVE_RESILIENCE = withRate(8, 16);
    /** Address histories and freeze backfill: 2 requests/s (burst 4), their own circuit breaker. */
    static final io.openchain.blockchain.transport.ResilienceConfig BACKGROUND_RESILIENCE = withRate(2, 4);

    private static io.openchain.blockchain.transport.ResilienceConfig withRate(double perSecond, int burst) {
        var d = io.openchain.blockchain.transport.ResilienceConfig.defaults();
        return new io.openchain.blockchain.transport.ResilienceConfig(d.maxAttempts(), d.initialBackoff(), d.maxBackoff(), d.jitter(),
                perSecond, burst, d.failureThreshold(), d.openDuration());
    }

    /** One client on the chain's endpoints (primary with the API key, then fallbacks); {@code suffix} names its breakers. */
    static HttpJsonTransport transport(Network n, OciProperties.Chain c, String suffix, io.openchain.blockchain.transport.ResilienceConfig r) {
        Map<String, String> headers = c.getApiKeyHeader() != null && c.getApiKey() != null && !c.getApiKey().isBlank()
                ? Map.of(c.getApiKeyHeader(), c.getApiKey()) : Map.of();
        List<RpcEndpoint> endpoints = new java.util.ArrayList<>();
        endpoints.add(new RpcEndpoint(n.id() + suffix + "-primary", URI.create(c.getUrl()), headers, null, null, r));
        int k = 0;
        for (String u : c.getFallbackUrls()) {
            if (u == null || u.isBlank()) continue;
            endpoints.add(new RpcEndpoint(n.id() + suffix + "-fallback-" + (++k), URI.create(u.strip()), Map.of(), null, null, r));
        }
        return new HttpJsonTransport(endpoints, java.time.Clock.systemUTC(), io.openchain.blockchain.transport.Sleeper.SYSTEM);
    }

    private BlockchainProvider provider(Network n, OciProperties.Chain c, HttpJsonTransport http, String suffix) {
        return switch (n) {
            case TRON -> new TronGridProvider("tron-trongrid" + suffix, http, tokens, TronConfig.defaults());
            case ETHEREUM, BSC, POLYGON -> new EvmJsonRpcProvider(n.id() + "-rpc" + suffix, n, new JsonRpcClient(http), tokens,
                    EvmConfig.defaults(n).withHistoryChunks(c.getHistory().getChunkSize(), c.getHistory().getMinChunkSize()));
            default -> throw new IllegalArgumentException("no provider adapter for " + n.id());
        };
    }

    /** Registers (re-applying stored freezes) and, if configured, starts the freeze backfill of a real network. */
    private void startFreezeBackfill(Network n, BlockchainProvider p, OciProperties.FreezeBackfill fb) {
        if (!fb.isEnabled()) return;
        if (!(p instanceof FreezeLogScanner scanner) || StablecoinFreezeSources.defaults().forNetwork(n).isEmpty()) {
            log.warn("freeze backfill of {} ignored: no official freeze source / log scan for this network", n.id());
            return;
        }
        try {
            backfills.register(scanner, new FreezeBackfill.Config(fb.getFromBlock(), fb.getToBlock(), fb.getChunkSize(),
                    fb.getMinChunkSize(), fb.getPause()));
            if (fb.isRunOnStartup()) backfills.trigger(n);
        } catch (RuntimeException e) {
            log.error("freeze backfill of {} not started: {}", n.id(), e.getMessage());
        }
    }

    /**
     * Wraps a background task so that no failure cancels its schedule: a periodic task that throws is never run again
     * by the executor, and an Error (StackOverflowError, LinkageError...) used to escape silently (code review I17),
     * stopping for good the working-set trim, the alert dispatch or the escalation. Errors are logged as such.
     */
    static Runnable guard(String name, Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                log.warn("background task '{}' failed: {}", name, e.toString());
            } catch (Throwable e) {
                log.error("background task '{}' failed with an error, it stays scheduled: {}", name, e.toString(), e);
            }
        };
    }

    /** ADR-0024 step 3d: reloads the working-set snapshot, if enabled and usable, before the real indexers start. */
    private void restoreWorkingSet(boolean demo) {
        if (snapshots == null) return;
        if (demo) { log.warn("working-set snapshot ignored in demo mode (simulated chains are not persisted)"); return; }
        if (props.getPersistence().isIntelligenceJournal()) {
            log.warn("working-set snapshot disabled: the intelligence journal is on (the two would restore the same state twice)");
            return;
        }
        snapshotsActive = true;
        snapshots.restoreAtStartup();
        Duration every = props.getPersistence().getSnapshotInterval();
        if (every.isPositive()) {
            scheduler.scheduleWithFixedDelay(guard("working-set snapshot", snapshots::writePeriodic), every.toMillis(), every.toMillis(),
                    TimeUnit.MILLISECONDS);
            log.info("working-set snapshot every {} (after a crash, at most that much indexing is done again)", every);
        } else {
            log.warn("periodic working-set snapshots disabled: after a crash the last snapshot is reloaded and everything indexed "
                    + "since it is indexed again (or skipped beyond max-resume-gap)");
        }
    }

    /**
     * Orderly shutdown. With the working-set snapshot active: no new poll is scheduled, polls in progress get
     * {@code snapshot-shutdown-timeout} to finish (then they are interrupted), and the snapshot is written while nothing
     * is indexed. Without it: everything stops at once, as before.
     */
    @Override public void destroy() {
        if (!snapshotsActive) { scheduler.shutdownNow(); return; }
        scheduler.shutdown(); // periodic tasks are cancelled, running ones finish
        try {
            Duration wait = props.getPersistence().getSnapshotShutdownTimeout();
            if (!scheduler.awaitTermination(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("shutdown: background tasks still running after {}; interrupting them before the snapshot", wait);
                scheduler.shutdownNow();
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        snapshots.writeAtShutdown(); // under the ingestion lock: an interrupted poll cannot be half-way through an event
        scheduler.shutdownNow();
    }
}
