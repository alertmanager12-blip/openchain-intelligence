package io.openchain.app.demo;

import io.openchain.alerting.alert.Alert;
import io.openchain.alerting.alert.AlertService;
import io.openchain.app.chain.DemoLedgerProvider;
import io.openchain.app.chain.EventHistory;
import io.openchain.app.labels.LabelCatalog;
import io.openchain.blockchain.tron.TronAddress;
import io.openchain.core.detection.Signal;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.core.model.TokenTransfer;
import io.openchain.core.provider.BlockchainProvider;
import io.openchain.intelligence.IntelligencePipeline;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.LabelCategory;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Demo scenarios A-E: FICTITIOUS on-chain stories injected into the simulated TRON ledger and processed by the
 * real pipeline (indexer -> detection -> risk -> rule engine -> alert -> notification sink). Every address involved
 * is labelled {@code demo-scenarios} / "fictional"; nothing here designates a real person or wallet.
 */
public final class DemoScenarios {

    public static final String LABEL_SOURCE = "demo-scenarios";
    public static final Token TRON_USDT = new Token(Network.TRON, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "USDT", 6);

    /** One planned scenario. {@code subjects} are the addresses whose signals/risk are reported. */
    public record Scenario(String id, String title, String story, Network network, Map<String, String> roles, List<String> subjects,
                           List<String> txHashes, String expectedRule, String expectedSignal) {}

    private final IntelligencePipeline intelligence;
    private final AlertService alerts;
    private final EventHistory history;
    private final DemoNotificationSink sink;
    private final List<Scenario> scenarios = new CopyOnWriteArrayList<>();
    private volatile BlockchainProvider provider;

    public DemoScenarios(IntelligencePipeline intelligence, AlertService alerts, EventHistory history, DemoNotificationSink sink) {
        this.intelligence = intelligence;
        this.alerts = alerts;
        this.history = history;
        this.sink = sink;
    }

    public List<Scenario> scenarios() { return List.copyOf(scenarios); }

    // ---- planning -----------------------------------------------------------------------------------------------

    /**
     * Injects the five scenarios into already-mined but not yet processed blocks (between head-160 and head-80), so
     * they are indexed with the seed history. {@code sanctioned} is the dedicated demo-sanctioned address.
     */
    public void plan(DemoLedgerProvider ledger, long head, List<Address> pool, Address sanctioned, LabelCatalog labels) {
        if (ledger.network() != Network.TRON || head < 200 || pool.size() < 8) return;
        long b = head - 160;
        Address faucet = ledger.faucet();
        Instant at = Instant.parse("2026-01-01T00:00:00Z");

        // A - large transfer: a pool wallet receives 3 x 900k USDT then moves 2M USDT in one transfer
        Address a1 = pool.get(5), a2 = pool.get(6);
        List<String> ta = new ArrayList<>();
        // routine activity first: a baseline of small payments the amount detector compares against
        for (int i = 0; i < 12; i++) ta.add(inject(ledger, b - 30 + i, "A-routine-" + i, a1, pool.get(10 + i % 5), units(100 + 7L * i), null));
        for (int i = 0; i < 3; i++) ta.add(inject(ledger, b + i, "A-fund-" + i, faucet, a1, units(900_000), null));
        ta.add(inject(ledger, b + 5, "A-large", a1, a2, units(2_000_000), null));
        add("A", "Large USDT transfer", "A wallet with routine small payments receives 2.7M USDT from a (fictional) exchange faucet and sends 2M USDT in a single transfer.",
                roles("sender", a1, "recipient", a2), List.of(a1), ta, "large-usdt-transfer", "UNUSUAL_AMOUNT");

        // B - indirect exposure: sanctioned -> X -> Y (2 hops)
        Address x = fresh("B-intermediary"), y = fresh("B-recipient");
        List<String> tb = List.of(
                inject(ledger, b + 8, "B-source-funding", faucet, sanctioned, units(50_000), Duration.ofDays(30)),
                inject(ledger, b + 10, "B-hop1", sanctioned, x, units(20_000), null),
                inject(ledger, b + 14, "B-hop2", x, y, units(15_000), null));
        add("B", "Indirect exposure (2 hops) to a listed address",
                "A (demo-)sanctioned address pays an intermediary, which forwards most of it to a fresh wallet: the recipient is 2 hops away.",
                roles("listedSource", sanctioned, "intermediary", x, "recipient", y), List.of(y, x), tb, "sanctioned-exposure-signal", "INDIRECT_EXPOSURE");

        // C - wallet draining: 100k historical deposit, then 95% leaves within 60 s
        Address w = fresh("C-wallet"), z1 = fresh("C-dest-1"), z2 = fresh("C-dest-2");
        List<String> tc = List.of(
                inject(ledger, b + 20, "C-deposit", faucet, w, units(100_000), Duration.ofHours(2)),
                inject(ledger, b + 30, "C-out-1", w, z1, units(60_000), null),
                inject(ledger, b + 40, "C-out-2", w, z2, units(35_000), null));
        add("C", "Wallet draining", "A wallet holding 100k USDT (historical deposit) sends 95% of it to two fresh addresses within one minute.",
                roles("wallet", w, "destination1", z1, "destination2", z2), List.of(w), tc, "wallet-draining", "RAPID_DRAIN");

        // D - fund splitting: 300k historical deposit, then 21 x 10k to 21 fresh recipients in one block
        Address d = fresh("D-wallet");
        List<String> td = new ArrayList<>();
        td.add(inject(ledger, b + 50, "D-deposit", faucet, d, units(300_000), Duration.ofHours(2)));
        Map<String, String> rd = roles("wallet", d);
        for (int i = 1; i <= 21; i++) {
            Address r = fresh("D-recipient-" + i);
            td.add(inject(ledger, b + 60, "D-split-" + i, d, r, units(10_000), null));
            if (i <= 3) rd.put("recipient" + i, r.value());
        }
        add("D", "Fund splitting (fan-out)", "A wallet splits 210k USDT into 21 transfers of 10k to 21 fresh addresses in one block.",
                rd, List.of(d), td, "fund-splitting", "FLOW_CONCENTRATION");

        // E - dormant reactivation: deposit 400 days ago (historical), then a transfer now
        Address e = fresh("E-wallet"), ex = fresh("E-destination");
        List<String> te = List.of(
                inject(ledger, b + 70, "E-old-deposit", faucet, e, units(50_000), Duration.ofDays(400)),
                inject(ledger, b + 80, "E-reactivation", e, ex, units(30_000), null));
        add("E", "Dormant wallet reactivation", "A wallet funded 400 days ago (historical activity) and silent since moves 30k USDT.",
                roles("wallet", e, "destination", ex), List.of(e), te, "dormant-wallet-reactivation", "DORMANT_REACTIVATION");

        for (Scenario s : scenarios) {
            for (Map.Entry<String, String> r : s.roles().entrySet()) {
                Address addr = new Address(Network.TRON, r.getValue());
                if (addr.equals(sanctioned) || addr.equals(faucet)) continue;
                labels.save(new AddressLabel(addr, LabelCategory.OTHER, "DEMO scenario " + s.id() + " - " + r.getKey() + " (fictional address)",
                        LABEL_SOURCE, 1.0, at, Signal.EvidenceLevel.ANOMALY, "demo://scenario/" + s.id()));
            }
        }
        this.provider = ledger;
    }

    private static String inject(DemoLedgerProvider l, long block, String seed, Address from, Address to, BigInteger raw, Duration backdate) {
        String hash = l.hashOf("scenario:" + seed);
        l.inject(block, new DemoLedgerProvider.Injection(hash, from, to, TRON_USDT, raw, backdate));
        return hash;
    }

    private void add(String id, String title, String story, Map<String, String> roles, List<Address> subjects, List<String> txs,
                     String rule, String signal) {
        scenarios.add(new Scenario(id, title, story, Network.TRON, roles, subjects.stream().map(Address::value).toList(), txs, rule, signal));
    }

    private static Map<String, String> roles(Object... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], ((Address) kv[i + 1]).value());
        return m;
    }

    private static BigInteger units(long whole) { return BigInteger.valueOf(whole).multiply(BigInteger.TEN.pow(6)); }

    /** Deterministic, well-formed but fictional TRON address. */
    static Address fresh(String seed) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(("oci-demo-scenario:" + seed).getBytes(StandardCharsets.UTF_8));
            return new Address(Network.TRON, TronAddress.fromHex(HexFormat.of().formatHex(h).substring(0, 40)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- evidence -----------------------------------------------------------------------------------------------

    /** Scenario id for an engine event id ({@code network:hash:log} or {@code signal:<id>}), if it belongs to one. */
    public Optional<String> scenarioOf(String eventId) {
        String hash = null;
        if (eventId.startsWith("signal:")) {
            hash = intelligence.signals().findById(eventId.substring(7)).map(Signal::txHash).orElse(null);
        } else {
            String[] p = eventId.split(":");
            if (p.length == 3) hash = p[1];
        }
        if (hash == null) return Optional.empty();
        for (Scenario s : scenarios) if (s.txHashes().contains(hash)) return Optional.of(s.id());
        return Optional.empty();
    }

    /** Live evidence for every scenario, as produced by the real pipeline. */
    public List<Map<String, Object>> evidence() {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Alert> allAlerts = alerts.repository().findAll();
        for (Scenario s : scenarios) {
            Set<String> eventIds = new LinkedHashSet<>();
            List<Map<String, Object>> events = new ArrayList<>();
            BlockchainProvider p = provider;
            for (String h : s.txHashes()) {
                if (p == null) break;
                p.getTransactionReceipt(h).ifPresent(r -> {
                    for (TokenTransfer t : r.transfers()) {
                        eventIds.add(t.eventId());
                        if (events.size() < 6) {
                            Map<String, Object> ev = new LinkedHashMap<>();
                            ev.put("eventId", t.eventId());
                            ev.put("indexed", history.find(t.eventId()).isPresent() || intelligence.flows().contains(t.eventId()));
                            ev.put("block", t.blockNumber());
                            ev.put("from", t.from().value());
                            ev.put("to", t.to().value());
                            ev.put("amount", t.amount().stripTrailingZeros().toPlainString() + " " + t.token().symbol());
                            events.add(ev);
                        }
                    }
                });
            }
            List<Map<String, Object>> signals = new ArrayList<>();
            Map<String, Object> risk = new LinkedHashMap<>();
            Set<String> signalEventIds = new LinkedHashSet<>();
            for (String subject : s.subjects()) {
                Address a = new Address(s.network(), subject);
                for (Signal sig : intelligence.signals().findByAddress(a)) {
                    if (sig.txHash() == null || !s.txHashes().contains(sig.txHash())) continue;
                    signalEventIds.add("signal:" + sig.id());
                    signals.add(Map.of("id", sig.id(), "type", sig.type().name(), "address", subject, "severity", sig.severity().name(),
                            "confidence", sig.confidence(), "evidenceLevel", sig.evidenceLevel().name()));
                }
                intelligence.scoring().latest(a).ifPresent(r -> risk.put(subject, Map.of("score", r.score(), "level", r.level().name())));
            }
            List<Map<String, Object>> scenarioAlerts = new ArrayList<>();
            int notified = 0;
            for (Alert al : allAlerts) {
                boolean mine = al.matchedEventIds().stream().anyMatch(id -> eventIds.contains(id) || signalEventIds.contains(id));
                if (!mine) continue;
                var notes = sink.forAlert(al.id());
                notified += notes.size();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", al.id());
                m.put("ruleId", al.ruleId());
                m.put("severity", al.severity().name());
                m.put("lifecycleStatus", al.status().name());
                m.put("occurrenceCount", al.occurrenceCount());
                m.put("notifications", notes.stream().map(n -> Map.of("deliveryId", n.deliveryId(), "at", n.at().toString(),
                        "channel", DemoNotificationSink.CONNECTOR_ID + " (simulated)")).toList());
                scenarioAlerts.add(m);
            }
            boolean indexed = !events.isEmpty() && events.stream().allMatch(ev -> Boolean.TRUE.equals(ev.get("indexed")));
            Map<String, Object> checks = new LinkedHashMap<>();
            checks.put("eventsIndexed", indexed);
            checks.put("expectedSignalDetected", signals.stream().anyMatch(x -> s.expectedSignal().equals(x.get("type"))));
            checks.put("riskScored", !risk.isEmpty());
            checks.put("expectedRuleTriggered", scenarioAlerts.stream().anyMatch(x -> s.expectedRule().equals(x.get("ruleId"))));
            checks.put("notificationSimulated", notified > 0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id());
            m.put("title", s.title());
            m.put("fictitious", true);
            m.put("disclaimer", "Fictitious demo data: generated addresses and transfers on a simulated chain.");
            m.put("story", s.story());
            m.put("network", s.network().id());
            m.put("roles", s.roles());
            m.put("expectedRule", s.expectedRule());
            m.put("expectedSignal", s.expectedSignal());
            m.put("checks", checks);
            m.put("events", events);
            m.put("eventCount", eventIds.size());
            m.put("signals", signals);
            m.put("risk", risk);
            m.put("alerts", scenarioAlerts);
            out.add(m);
        }
        return out;
    }
}
