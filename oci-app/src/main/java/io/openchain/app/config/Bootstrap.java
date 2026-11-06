package io.openchain.app.config;

import io.openchain.alerting.alert.AlertService;
import io.openchain.alerting.notify.ConnectorRegistry;
import io.openchain.alerting.notify.DeliveryResult;
import io.openchain.alerting.notify.Notification;
import io.openchain.alerting.notify.NotificationConnector;
import io.openchain.alerting.pipeline.AlertingPipeline;
import io.openchain.alerting.rule.ActionType;
import io.openchain.app.chain.ChainIngestionService;
import io.openchain.app.chain.TimeShiftedProvider;
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
    public static final String DEMO_CONNECTOR_ID = "default-webhook";

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
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "oci-background");
        t.setDaemon(true);
        return t;
    });

    public Bootstrap(OciProperties props, LabelCatalog labels, RuleRegistry rules, ChainIngestionService chains,
                     AlertingPipeline alerting, AlertService alertService, ConnectorRegistry connectors, ApiKeyService keys,
                     UserDirectory users, TokenRegistry tokens, Clock clock) {
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
        if (demo) {
            demoKeys();
            connectors.register(new DemoLogConnector());
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
        users.put(new UserDirectory.User("u1", "admin@example.invalid", "Demo Admin", "ADMIN", now.minus(Duration.ofHours(1))));
        users.put(new UserDirectory.User("u2", "analyst@example.invalid", "Demo Analyst", "ANALYST", now.minus(Duration.ofHours(3))));
        users.put(new UserDirectory.User("u3", "viewer@example.invalid", "Demo Viewer", "VIEWER", null));
    }

    private void startSimulated(Network n) {
        OciProperties.Demo d = props.getDemo();
        Duration blockTime = n == Network.TRON ? Duration.ofSeconds(3) : Duration.ofSeconds(12); // as SimulatedChainProvider
        SimulatedChainProvider sim = new SimulatedChainProvider("sim-" + n.id(), n, d.getSeed() + n.ordinal(),
                d.getMaxTransfersPerBlock(), d.getAddressPoolSize(), tokens);
        int blocks = (int) Math.max(1, Duration.ofMinutes(d.getSeedMinutes()).toSeconds() / blockTime.toSeconds());
        sim.mine(blocks);
        seedDemoLabels(sim);
        Instant headTime = sim.getBlock(sim.head()).orElseThrow().timestamp();
        TimeShiftedProvider provider = new TimeShiftedProvider(sim, Duration.between(headTime, clock.instant()));
        int confirmations = Math.min(n.defaultConfirmations(), 12);
        IndexerConfig cfg = new IndexerConfig("demo-" + n.id(), 1, confirmations, n.maxReorgDepth(), 200);
        ChainIngestionService.NetworkRuntime rt = chains.register(provider, sim, cfg, blockTime);
        long t0 = System.nanoTime();
        chains.poll(rt);
        log.info("demo network {}: {} simulated blocks indexed in {} ms", n.id(), rt.lastIndexedBlock(), (System.nanoTime() - t0) / 1_000_000);
        if (d.isLive()) {
            scheduler.scheduleAtFixedRate(guard("mine " + n.id(), () -> { sim.mine(1); chains.poll(rt); }),
                    blockTime.toMillis(), blockTime.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** Fictional labels on simulated addresses so exposure, risk and graph views have something to show. */
    private void seedDemoLabels(SimulatedChainProvider sim) {
        List<Address> pool = sim.addressPool();
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        record L(LabelCategory c, String name, String source, double rel, EvidenceLevel ev, String ref) {}
        List<L> specs = List.of(
                new L(LabelCategory.SANCTIONED, "DEMO fictional sanctioned entity", "demo-sanctions-list", 1.0, EvidenceLevel.CONFIRMED_BY_SOURCE, "demo://sanctions/sim"),
                new L(LabelCategory.MIXER, "DEMO fictional mixer", "demo-community-feed", 0.6, EvidenceLevel.RISK_SIGNAL, "demo://feed/mixer"),
                new L(LabelCategory.SCAM, "DEMO fictional scam report", "demo-community-feed", 0.4, EvidenceLevel.RISK_SIGNAL, "demo://feed/scam"),
                new L(LabelCategory.EXCHANGE, "DEMO fictional exchange hot wallet", "demo-directory", 0.9, EvidenceLevel.ANOMALY, null),
                new L(LabelCategory.BRIDGE, "DEMO fictional bridge", "demo-directory", 0.9, EvidenceLevel.ANOMALY, null));
        for (int i = 0; i < specs.size() && i < pool.size(); i++) {
            L s = specs.get(i);
            labels.save(new AddressLabel(pool.get(pool.size() - 1 - i), s.c(), s.name(), s.source(), s.rel(), at, s.ev(), s.ref()));
        }
    }

    /** Demo sink for the {@code default-webhook} connector referenced by the repository rules: logs, sends nothing. */
    static final class DemoLogConnector implements NotificationConnector {
        @Override public String id() { return DEMO_CONNECTOR_ID; }
        @Override public ActionType type() { return ActionType.WEBHOOK; }
        @Override public DeliveryResult send(Notification n) {
            log.info("demo connector '{}': notification {} (not sent anywhere: demo mode)", DEMO_CONNECTOR_ID, n.deliveryId());
            return new DeliveryResult(true, false, 0, "logged by demo connector");
        }
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
            ChainIngestionService.NetworkRuntime rt = chains.register(p, null, IndexerConfig.of(n.id() + "-indexer", n, start), null);
            long every = props.getIngestion().getPollInterval().toMillis();
            scheduler.scheduleWithFixedDelay(guard("poll " + n.id(), () -> chains.poll(rt)), 0, every, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            log.error("chain {} not started: {}", id, e.getMessage());
        }
    }

    private static Runnable guard(String name, Runnable r) {
        return () -> {
            try { r.run(); } catch (RuntimeException e) { log.warn("background task '{}' failed: {}", name, e.toString()); }
        };
    }

    @Override public void destroy() { scheduler.shutdownNow(); }
}
