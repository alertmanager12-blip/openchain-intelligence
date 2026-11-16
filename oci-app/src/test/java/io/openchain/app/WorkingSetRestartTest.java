package io.openchain.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.openchain.app.persistence.PostgresSupport;
import io.openchain.app.persistence.WorkingSetSnapshotService;
import io.openchain.core.detection.Signal;
import io.openchain.core.model.Address;
import io.openchain.intelligence.IntelligencePipeline;
import io.openchain.intelligence.flow.ValueFlow;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-0024 step 3d end to end, against PostgreSQL 17 and a local Ethereum JSON-RPC stub: the real application indexes
 * blocks 1..40, stops in order (working-set snapshot written), and a second application started on the same database
 * resumes at block 41 without fetching any earlier block again. After both have indexed blocks 1..80, the intelligence
 * (flows, wallet profiles, signals, risk scores) equals that of a third application that indexed 1..80 without a restart.
 * The traffic crosses the restart on purpose: funds received before it are forwarded after it.
 */
@PostgresSupport.RequiresDocker
class WorkingSetRestartTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final int CONFIRMATIONS = 12;
    static final long FIRST_STOP = 40, LAST = 80;
    /** Block times: recent enough that no working-set retention trims anything during the test. */
    static final long T0 = Instant.now().minus(Duration.ofHours(2)).getEpochSecond();

    FakeEthereum node;

    @BeforeEach void node() throws Exception { node = new FakeEthereum(); }
    @AfterEach void stop() { node.close(); }

    @Test void aRestartedApplicationResumesWithTheSameIntelligenceAsOneThatNeverStopped() throws Exception {
        PostgresSupport.Database db = PostgresSupport.newDatabase();
        State restarted;

        node.head.set(FIRST_STOP + CONFIRMATIONS);
        try (ConfigurableApplicationContext first = start(db)) {
            awaitIndexed(first, FIRST_STOP);
            assertThat(pipeline(first).flows().size()).as("blocks 1..40 indexed").isPositive();
            assertThat(pipeline(first).signals().size()).as("the traffic produces signals before the restart").isPositive();
            assertThat(gauge(first, "oci.snapshot.restore.outcome", "outcome", "none")).isEqualTo(1);
            assertThat(gauge(first, "oci.snapshot.restored.items", "kind", "flows")).isNaN();
        } // orderly shutdown: snapshot written
        NamedParameterJdbcTemplate dbt = jdbc(db);
        assertThat(dbt.queryForObject("select count(*) from working_set_snapshots where completed_at is not null", Map.of(),
                Integer.class)).as("snapshot written at shutdown").isEqualTo(1);

        node.fullFetches.clear();
        node.head.set(LAST + CONFIRMATIONS);
        try (ConfigurableApplicationContext second = start(db)) {
            WorkingSetSnapshotService snapshots = second.getBean(WorkingSetSnapshotService.class);
            assertThat(snapshots.lastRestore()).isEqualTo(WorkingSetSnapshotService.Outcome.RESTORED);
            // metrics (design § 4.6): what was restored, how long it took, how old it was
            assertThat(gauge(second, "oci.snapshot.restore.outcome", "outcome", "restored")).isEqualTo(1);
            assertThat(gauge(second, "oci.snapshot.restore.outcome", "outcome", "none")).isZero();
            assertThat(gauge(second, "oci.snapshot.restored.items", "kind", "flows")).isEqualTo(FIRST_STOP * 3);
            assertThat(gauge(second, "oci.snapshot.restored.items", "kind", "profiles")).isPositive();
            assertThat(gauge(second, "oci.snapshot.restore.seconds", null, null)).isGreaterThanOrEqualTo(0);
            assertThat(gauge(second, "oci.snapshot.age.seconds", null, null)).isPositive();
            assertThat(gauge(second, "oci.snapshot.write.seconds", null, null)).as("nothing written yet in this run").isNaN();
            assertThat(second.getBean(io.micrometer.core.instrument.MeterRegistry.class).get("oci.snapshot.failures")
                    .tag("phase", "restore").functionCounter().count()).isZero();
            awaitIndexed(second, LAST);
            assertThat(node.fullFetches.keySet()).as("no block indexed before the restart is fetched again")
                    .allMatch(n -> n > FIRST_STOP).contains(FIRST_STOP + 1, LAST);
            restarted = State.of(pipeline(second));
        }

        node.fullFetches.clear();
        State reference;
        try (ConfigurableApplicationContext never = start(PostgresSupport.newDatabase())) {
            awaitIndexed(never, LAST);
            reference = State.of(pipeline(never));
        }

        assertThat(reference.flows()).as("reference run indexed the whole range").hasSize((int) (LAST * 3));
        assertThat(restarted.flows()).isEqualTo(reference.flows());
        assertThat(differences(restarted.profiles(), reference.profiles())).as("profiles that differ (restarted vs reference)").isEmpty();
        assertThat(restarted.signals()).as("signals, including those crossing the restart").isEqualTo(reference.signals());
        assertThat(restarted.scores()).isEqualTo(reference.scores());
        assertThat(reference.signals()).as("the comparison is not vacuous").isNotEmpty();
    }

    // ---- comparison --------------------------------------------------------------------------------------------------

    /** What an analyst sees; detection and computation instants (wall clock) are left out. */
    record State(Set<String> flows, Map<String, String> profiles, Set<String> signals, Map<String, String> scores) {
        static State of(IntelligencePipeline p) {
            Set<String> flows = p.flows().all().stream().map(ValueFlow::eventId).collect(Collectors.toCollection(TreeSet::new));
            Set<Address> addresses = new java.util.HashSet<>();
            for (ValueFlow f : p.flows().all()) { addresses.add(f.from()); addresses.add(f.to()); }
            Map<String, String> profiles = new TreeMap<>();
            for (Address a : addresses) profiles.put(a.value(), wallClockFree(String.valueOf(p.profiles().profile(a))));
            Set<String> signals = p.signals().findAll().stream().map(WorkingSetRestartTest::signal)
                    .collect(Collectors.toCollection(TreeSet::new));
            Map<String, String> scores = new TreeMap<>();
            p.scoring().repository().all().forEach((a, history) -> scores.put(a.value(),
                    history.stream().map(s -> s.score() + " " + s.level()).collect(Collectors.joining(", "))));
            return new State(flows, profiles, signals, scores);
        }
    }

    /**
     * A profile embeds its risk scores, which carry when they were computed and the age of each contribution at that
     * moment ("age 1h" in one run, "age 2h" in the other a few seconds later): wall-clock values, not results. They are
     * blanked; the decay, q, p and points they lead to are still compared.
     */
    static String wallClockFree(String profile) {
        return profile.replaceAll("computedAt=[^,\\]]+", "computedAt=*").replaceAll("ageSeconds=\\d+", "ageSeconds=*")
                .replaceAll(", age [^,]+, decay", ", age *, decay");
    }

    /** Entries that differ, each shown as "key: restarted ≠ reference" (readable failure on large maps). */
    static List<String> differences(Map<String, String> restarted, Map<String, String> reference) {
        Set<String> keys = new TreeSet<>(restarted.keySet());
        keys.addAll(reference.keySet());
        return keys.stream().filter(k -> !Objects.equals(restarted.get(k), reference.get(k)))
                .map(k -> k + ":\n  restarted " + restarted.get(k) + "\n  reference " + reference.get(k)).toList();
    }

    static String signal(Signal s) {
        return String.join("|", s.type().name(), s.address().value(), Objects.toString(s.txHash()), s.severity().name(),
                s.evidenceLevel().name(), s.detectorId(), String.valueOf(s.confidence()), s.summary());
    }

    // ---- application -------------------------------------------------------------------------------------------------

    ConfigurableApplicationContext start(PostgresSupport.Database db) {
        Map<String, Object> p = new HashMap<>();
        p.put("spring.datasource.url", db.url());
        p.put("spring.datasource.username", db.username());
        p.put("spring.datasource.password", db.password());
        p.put("oci.security.crypto-master-key", PersistenceRestartTest.MASTER_KEY);
        p.put("server.port", "0");
        p.put("oci.demo.enabled", "false");
        p.put("oci.ingestion.poll-interval", "100ms");
        p.put("oci.persistence.working-set-snapshot", "true");
        p.put("oci.persistence.snapshot-shutdown-timeout", "10s");
        p.put("oci.chains.ethereum.enabled", "true");
        p.put("oci.chains.ethereum.url", node.url());
        p.put("oci.chains.ethereum.start-block", "1");
        p.put("oci.chains.ethereum.real-balance-max-lookups-per-block", "0");
        p.put("oci.chains.ethereum.history.enabled", "false");
        String[] args = p.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
        return new SpringApplicationBuilder(OciApplication.class, ApiTestSupport.TestBeans.class).profiles("test").run(args);
    }

    static double gauge(ConfigurableApplicationContext ctx, String name, String tag, String value) {
        var search = ctx.getBean(io.micrometer.core.instrument.MeterRegistry.class).get(name);
        return (tag == null ? search : search.tag(tag, value)).gauge().value();
    }

    static IntelligencePipeline pipeline(ConfigurableApplicationContext ctx) { return ctx.getBean(IntelligencePipeline.class); }

    static NamedParameterJdbcTemplate jdbc(PostgresSupport.Database db) { return new NamedParameterJdbcTemplate(db.dataSource()); }

    static void awaitIndexed(ConfigurableApplicationContext ctx, long block) throws InterruptedException {
        NamedParameterJdbcTemplate jdbc = ctx.getBean(NamedParameterJdbcTemplate.class);
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        Long last = null;
        while (System.nanoTime() < deadline) {
            List<Long> rows = jdbc.queryForList("select last_block from indexer_checkpoints where network = 'ethereum'", Map.of(), Long.class);
            last = rows.isEmpty() ? null : rows.getFirst();
            if (last != null && last >= block) {
                assertThat(last).as("never indexes beyond the confirmed head").isEqualTo(block);
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("block " + block + " not indexed in 90 s (last indexed: " + last + ")");
    }

    // ---- fake Ethereum node ------------------------------------------------------------------------------------------

    /**
     * Deterministic chain: every block n carries three successful native transfers.
     * <ul>
     *   <li>a funder pays 10 ETH to a relay of block n, and that relay forwards 9.99 ETH to a collector in block n+1
     *       (fast forwarding; the relay of block 40 is paid before the restart and forwards after it);</li>
     *   <li>a hub pays 0.1 ETH to a new address every block (fan-out);</li>
     *   <li>three wallets pass 1 ETH around in a circle.</li>
     * </ul>
     */
    static final class FakeEthereum implements AutoCloseable {
        final AtomicLong head = new AtomicLong();
        final Map<Long, AtomicInteger> fullFetches = new ConcurrentHashMap<>();
        private final HttpServer server;

        FakeEthereum() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                JsonNode req = JSON.readTree(ex.getRequestBody().readAllBytes());
                String out = req.isArray() ? batch((ArrayNode) req) : JSON.writeValueAsString(answer(req));
                byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
            });
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); }

        private String batch(ArrayNode reqs) {
            ArrayNode out = JSON.createArrayNode();
            reqs.forEach(r -> out.add(answer(r)));
            return JSON.writeValueAsString(out);
        }

        private ObjectNode answer(JsonNode req) {
            ObjectNode res = JSON.createObjectNode().put("jsonrpc", "2.0");
            res.set("id", req.get("id"));
            JsonNode params = req.path("params");
            switch (req.path("method").asString()) {
                case "eth_blockNumber" -> res.put("result", hex(head.get()));
                case "eth_getBlockByNumber" -> {
                    String tag = params.get(0).asString();
                    long n = "finalized".equals(tag) ? head.get() - CONFIRMATIONS : "latest".equals(tag) ? head.get() : Long.decode(tag);
                    boolean full = params.get(1).asBoolean();
                    if (n < 0 || n > head.get()) res.putNull("result");
                    else {
                        if (full) fullFetches.computeIfAbsent(n, k -> new AtomicInteger()).incrementAndGet();
                        res.set("result", block(n, full));
                    }
                }
                case "eth_getBlockReceipts" -> {
                    long n = Long.decode(params.get(0).asString());
                    ArrayNode rcs = JSON.createArrayNode();
                    if (n <= head.get()) for (Tx t : txs(n)) rcs.add(receipt(n, t));
                    res.set("result", n <= head.get() ? rcs : null);
                }
                case "eth_getCode" -> res.put("result", "0x");
                case "eth_getLogs" -> res.set("result", JSON.createArrayNode());
                default -> res.set("error", JSON.createObjectNode().put("code", -32601).put("message", "method not found"));
            }
            return res;
        }

        record Tx(String hash, String from, String to, String value) {}

        static String hex(long v) { return "0x" + Long.toHexString(v); }
        static String blockHash(long n) { return n < 0 ? "0x" + "0".repeat(64) : "0x%064x".formatted(0xb10c_0000L + n); }
        static String addr(long kind, long i) { return "0x%040x".formatted(kind * 1_000_000L + i); }
        static final String ETH = "1000000000000000000";

        static List<Tx> txs(long n) {
            String funder = addr(1, n % 4), relay = addr(2, n), collector = addr(3, 1), hub = addr(4, 1), fresh = addr(5, n);
            String[] circle = {addr(6, 1), addr(6, 2), addr(6, 3)};
            int c = (int) (n % 3);
            String forward = n > 1 ? "0x" + new java.math.BigInteger("9990000000000000000").toString(16) : null;
            Tx first = n > 1 ? new Tx(txHash(n, 0), addr(2, n - 1), collector, forward)
                    : new Tx(txHash(n, 0), circle[0], circle[1], "0x" + new java.math.BigInteger(ETH).toString(16));
            return List.of(
                    first,
                    new Tx(txHash(n, 1), funder, relay, "0x" + new java.math.BigInteger(ETH).multiply(java.math.BigInteger.TEN).toString(16)),
                    n % 2 == 0 ? new Tx(txHash(n, 2), hub, fresh, "0x16345785d8a0000")
                            : new Tx(txHash(n, 2), circle[c], circle[(c + 1) % 3], "0x" + new java.math.BigInteger(ETH).toString(16)));
        }

        static String txHash(long n, int i) { return "0x%064x".formatted(0x7a00_0000L + n * 10 + i); }

        ObjectNode block(long n, boolean full) {
            ObjectNode b = JSON.createObjectNode().put("number", hex(n)).put("hash", blockHash(n)).put("parentHash", blockHash(n - 1))
                    .put("timestamp", hex(T0 + n * 12));
            ArrayNode txs = b.putArray("transactions");
            for (Tx t : txs(n)) {
                if (!full) { txs.add(t.hash()); continue; }
                txs.addObject().put("hash", t.hash()).put("from", t.from()).put("to", t.to()).put("value", t.value())
                        .put("input", "0x").put("gasPrice", "0x1").put("blockNumber", hex(n)).put("blockHash", blockHash(n));
            }
            return b;
        }

        ObjectNode receipt(long n, Tx t) {
            ObjectNode r = JSON.createObjectNode().put("transactionHash", t.hash()).put("status", "0x1").put("gasUsed", "0x5208")
                    .put("effectiveGasPrice", "0x1").put("blockNumber", hex(n)).put("blockHash", blockHash(n));
            r.putArray("logs");
            return r;
        }
    }
}
