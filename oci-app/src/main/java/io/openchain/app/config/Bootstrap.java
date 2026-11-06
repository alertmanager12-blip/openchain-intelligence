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
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "oci-background");
        t.setDaemon(true);
        return t;
    });

    public Bootstrap(OciProperties props, LabelCatalog labels, RuleRegistry rules, ChainIngestionService chains,
                     AlertingPipeline alerting, AlertService alertService, ConnectorRegistry connectors, ApiKeyService keys,
                     UserDirectory users, TokenRegistry tokens, Clock clock, ObjectProvider<DemoNotificationSink> demoSink,
                     ObjectProvider<DemoScenarios> demoScenarios, FreezeBackfillService backfills) {
        this.backfills = backfills;
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
        if (demo) for (String id : props.getDemo().getNetworks()) startSimulated(Network.fromId(id));
        props.getChains().forEach(this::startRealChain);

        Duration dispatch = props.getIngestion().getDispatchInterval();
        scheduler.scheduleWithFixedDelay(guard("dispatch", () -> alerting.dispatch(200)), dispatch.toMillis(), dispatch.toMillis(), TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(guard("escalation", alertService::escalateOverdue), 60, 60, TimeUnit.SECONDS);
        alerting.dispatch(1_000);
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

    private void startRealChain(String id, OciProperties.Chain c) {
        if (!c.isEnabled()) return;
        try {
            Network n = Network.fromId(id);
            if (chains.runtime(n).isPresent()) {
                log.warn("chain {} is already simulated (demo mode); real provider ignored", id);
                return;
            }
            Map<String, String> headers = c.getApiKeyHeader() != null && c.getApiKey() != null && !c.getApiKey().isBlank()
                    ? Map.of(c.getApiKeyHeader(), c.getApiKey()) : Map.of();
            HttpJsonTransport http = new HttpJsonTransport(new RpcEndpoint(n.id() + "-primary", URI.create(c.getUrl()), headers, null, null, null));
            BlockchainProvider p = switch (n) {
                case TRON -> new TronGridProvider("tron-trongrid", http, tokens, TronConfig.defaults());
                case ETHEREUM, BSC, POLYGON -> new EvmJsonRpcProvider(n.id() + "-rpc", n, new JsonRpcClient(http), tokens, EvmConfig.defaults(n));
                default -> throw new IllegalArgumentException("no provider adapter for " + n.id());
            };
            long start = c.getStartBlock() >= 0 ? c.getStartBlock() : Math.max(0, p.getNetworkStatus().headBlock() + c.getStartBlock());
            IndexerConfig cfg = IndexerConfig.of(n.id() + "-indexer", n, start).withApprovalsOfUnknownTokens(c.isApprovalsOfUnknownTokens())
                    .withWatchedContracts(n, java.util.Set.copyOf(c.getWatchedContracts()))
                    .withAdminChangesOfAllContracts(c.isAdminChangesOfAllContracts());
            ChainIngestionService.NetworkRuntime rt = chains.register(p, null, null, cfg, null);
            startFreezeBackfill(n, p, c.getFreezeBackfill());
            long every = props.getIngestion().getPollInterval().toMillis();
            scheduler.scheduleWithFixedDelay(guard("poll " + n.id(), () -> chains.poll(rt)), 0, every, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            log.error("chain {} not started: {}", id, e.getMessage());
        }
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

    private static Runnable guard(String name, Runnable r) {
        return () -> {
            try { r.run(); } catch (RuntimeException e) { log.warn("background task '{}' failed: {}", name, e.toString()); }
        };
    }

    @Override public void destroy() { scheduler.shutdownNow(); }
}
