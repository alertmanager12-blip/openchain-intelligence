package io.openchain.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.openchain.alerting.alert.Alert;
import io.openchain.alerting.alert.AlertService;
import io.openchain.alerting.alert.AlertStatus;
import io.openchain.alerting.alert.ConcurrentAlertModificationException;
import io.openchain.alerting.alert.Priority;
import io.openchain.alerting.engine.RuleMatch;
import io.openchain.alerting.notify.Notification;
import io.openchain.alerting.notify.delivery.DeliveryRecord;
import io.openchain.alerting.notify.delivery.DeliveryStatus;
import io.openchain.alerting.rule.ActionType;
import io.openchain.alerting.rule.RuleParser.Format;
import io.openchain.app.config.OciProperties;
import io.openchain.app.labels.JdbcLabelReportRepository;
import io.openchain.app.labels.LabelReportStore;
import io.openchain.app.rules.JdbcRuleRepository;
import io.openchain.app.rules.RuleRepository;
import io.openchain.app.security.ApiKeyService;
import io.openchain.app.security.JdbcApiKeyRepository;
import io.openchain.app.security.JdbcUserRepository;
import io.openchain.app.security.UserDirectory;
import io.openchain.app.webhooks.ConnectorRepository;
import io.openchain.app.webhooks.JdbcConnectorRepository;
import io.openchain.app.webhooks.WebhookRegistry;
import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.model.Network;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Round trips and invariants of every JDBC repository against a real PostgreSQL 17 (Testcontainers). */
@PostgresSupport.RequiresDocker
class JdbcRepositoriesPersistenceTest {

    static final String MASTER_KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    static final String OTHER_KEY = Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes());
    static final Instant T0 = Instant.parse("2026-06-01T12:00:00Z");

    DataSource ds;
    NamedParameterJdbcTemplate jdbc;
    TransactionRunner tx;

    @BeforeEach
    void setUp() {
        ds = PostgresSupport.newDatabase().dataSource();
        PostgresSupport.migrate(ds);
        jdbc = new NamedParameterJdbcTemplate(ds);
        TransactionTemplate t = new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx = new TransactionRunner() {
            @Override public <T> T inTransaction(java.util.function.Supplier<T> work) { return t.execute(s -> work.get()); }
        };
    }

    // ---- schema -------------------------------------------------------------------------------------------------

    @Test
    void flywayMigrationAppliesOnAnEmptyDatabaseAndIsIdempotent() {
        DataSource fresh = PostgresSupport.newDatabase().dataSource();
        MigrateResult first = PostgresSupport.migrate(fresh);
        assertThat(first.success).isTrue();
        assertThat(first.migrationsExecuted).isEqualTo(2);
        assertThat(first.targetSchemaVersion).isEqualTo("2");
        List<String> tables = new NamedParameterJdbcTemplate(fresh).queryForList(
                "select table_name from information_schema.tables where table_schema = 'public' order by table_name", Map.of(), String.class);
        assertThat(tables).contains("api_keys", "users", "connectors", "rules", "rule_versions", "alerts", "alert_history",
                "alert_comments", "deliveries", "label_reports", "backfill_progress", "backfill_freeze_events", "flyway_schema_history");
        assertThat(PostgresSupport.migrate(fresh).migrationsExecuted).isZero();
    }

    // ---- API keys -----------------------------------------------------------------------------------------------

    static OciProperties props(String pepper, boolean demo) {
        OciProperties p = new OciProperties();
        p.getSecurity().setApiKeyPepper(pepper);
        p.getSecurity().setKeyEnvironment("test");
        p.getDemo().setEnabled(demo);
        return p;
    }

    @Test
    void apiKeysSurviveANewServiceInstanceAndOnlyTheHashIsStored() {
        JdbcApiKeyRepository repo = new JdbcApiKeyRepository(jdbc);
        ApiKeyService first = new ApiKeyService(props("pepper-1", false), Clock.systemUTC(), repo, new PersistenceMode(true));
        ApiKeyService.Created c = first.create("ci", List.of("alerts:read", "rules:read"), Duration.ofDays(1), "admin");
        String secret = c.plaintext().substring(c.plaintext().lastIndexOf('_') + 1);

        ApiKeyService restarted = new ApiKeyService(props("pepper-1", false), Clock.systemUTC(), repo, new PersistenceMode(true));
        var principal = restarted.verify(c.plaintext()).orElseThrow();
        assertThat(principal.scopes()).containsExactlyInAnyOrder("alerts:read", "rules:read");
        assertThat(restarted.find(c.key().id()).orElseThrow().lastUsedAt()).isNotNull();
        assertThat(restarted.list()).extracting(ApiKeyService.StoredKey::id).containsExactly(c.key().id());

        String raw = jdbc.queryForObject("select row_to_json(k)::text from api_keys k", Map.of(), String.class);
        assertThat(raw).doesNotContain(secret).doesNotContain(c.plaintext());

        // a different pepper does not authenticate the stored key
        ApiKeyService otherPepper = new ApiKeyService(props("pepper-2", false), Clock.systemUTC(), repo, new PersistenceMode(true));
        assertThat(otherPepper.verify(c.plaintext())).isEmpty();

        restarted.revoke(c.key().id(), "admin");
        assertThat(first.verify(c.plaintext())).isEmpty();
        assertThat(first.find(c.key().id()).orElseThrow().revoked()).isTrue();
    }

    @Test
    void provisionedKeysAreIdempotentAndGeneratedDemoKeysAreRetired() {
        JdbcApiKeyRepository repo = new JdbcApiKeyRepository(jdbc);
        ApiKeyService s = new ApiKeyService(props("p", false), Clock.systemUTC(), repo, new PersistenceMode(true));
        String fixed = "oci_test_fixed001_FixedKeyForPersistenceTests00000000000001";
        var a = s.register(fixed, "static", List.of("alerts:read"), null, "config");
        var b = s.register(fixed, "static", List.of("alerts:read", "rules:read"), null, "config");
        assertThat(b.id()).isEqualTo(a.id());
        assertThat(s.find(a.id()).orElseThrow().scopes()).containsExactlyInAnyOrder("alerts:read", "rules:read");
        assertThatThrownBy(() -> s.register("oci_test_fixed001_ADifferentSecretWithTheSamePrefix0000000001", "x", List.of("*"), null, "config"))
                .isInstanceOf(IllegalStateException.class);

        for (int i = 0; i < 3; i++) {
            s.retireGenerated("demo-admin", "demo-bootstrap", "demo-bootstrap");
            s.create("demo-admin", List.of("*"), Duration.ofDays(1), "demo-bootstrap");
        }
        List<ApiKeyService.StoredKey> demo = s.list().stream().filter(k -> k.name().equals("demo-admin")).toList();
        assertThat(demo).hasSize(2); // the current key + the previous one (revoked); older ones purged
        assertThat(demo.stream().filter(k -> !k.revoked())).hasSize(1);
    }

    @Test
    void pepperIsRequiredWithPersistenceOutsideDemoMode() {
        JdbcApiKeyRepository repo = new JdbcApiKeyRepository(jdbc);
        assertThatThrownBy(() -> new ApiKeyService(props(null, false), Clock.systemUTC(), repo, new PersistenceMode(true)))
                .isInstanceOf(PersistenceStartupException.class).hasMessageContaining("OCI_SECURITY_API_KEY_PEPPER is required");
        assertThatThrownBy(() -> new ApiKeyService(props(DevDefaults.DEV_API_KEY_PEPPER, false), Clock.systemUTC(), repo, new PersistenceMode(true)))
                .isInstanceOf(PersistenceStartupException.class).hasMessageContaining("demo pepper");
        // demo mode: stable dev pepper, so a key survives a restart
        ApiKeyService demo1 = new ApiKeyService(props(null, true), Clock.systemUTC(), repo, new PersistenceMode(true));
        String k = demo1.create("demo", List.of("*"), Duration.ofDays(1), "t").plaintext();
        assertThat(new ApiKeyService(props(null, true), Clock.systemUTC(), repo, new PersistenceMode(true)).verify(k)).isPresent();
    }

    // ---- users, label reports -----------------------------------------------------------------------------------

    @Test
    void usersAndLabelReportsRoundTrip() {
        UserDirectory users = new UserDirectory(new JdbcUserRepository(jdbc));
        assertThat(users.putIfAbsent(new UserDirectory.User("u1", "a@example.invalid", "A", "ADMIN", T0))).isTrue();
        users.setRole("u1", "ANALYST");
        assertThat(users.putIfAbsent(new UserDirectory.User("u1", "a@example.invalid", "A", "ADMIN", T0))).isFalse();
        assertThat(new UserDirectory(new JdbcUserRepository(jdbc)).find("u1").orElseThrow().role()).isEqualTo("ANALYST");

        Clock clock = Clock.systemUTC();
        LabelReportStore reports = new LabelReportStore(clock, new JdbcLabelReportRepository(jdbc));
        reports.submit("tron", "TXYZ", "scam", "first", List.of("https://example.invalid/1"), "apikey:x");
        LabelReportStore.Report second = reports.submit("tron", "TABC", "mixer", "second", List.of(), "apikey:y");
        List<LabelReportStore.Report> listed = new LabelReportStore(clock, new JdbcLabelReportRepository(jdbc)).list();
        assertThat(listed).hasSize(2);
        assertThat(listed.getFirst().id()).isEqualTo(second.id());
        assertThat(listed.getLast().evidenceUrls()).containsExactly("https://example.invalid/1");
        assertThat(listed.getLast().status()).isEqualTo("PENDING");
    }

    // ---- connectors (encryption at rest) ------------------------------------------------------------------------

    static WebhookRegistry.Webhook webhook(String id, String type, String url) {
        return new WebhookRegistry.Webhook(id, type, "n-" + id, url, List.of("alert.opened"), true, T0, null, null);
    }

    @Test
    void connectorSecretsAndTargetsAreEncryptedAtRest() {
        SecretCipher cipher = SecretCipher.fromBase64(MASTER_KEY, false);
        JdbcConnectorRepository repo = new JdbcConnectorRepository(jdbc, cipher);
        String slackUrl = "https://hooks.slack.com/services/T000/B000/SLACKTOKEN123";
        String secret = "whsec_SigningSecretValue0123456789abcdefABCDEF";
        repo.insert(webhook("wh-slack", "SLACK", slackUrl), null, "admin");
        repo.insert(webhook("wh-hook", "WEBHOOK", "https://receiver.example.com/oci?token=QUERYTOKEN"), secret, "admin");
        repo.updateDelivery("wh-hook", T0.plusSeconds(5), "OK");

        Map<String, ConnectorRepository.Stored> byId = new LinkedHashMap<>();
        new JdbcConnectorRepository(jdbc, SecretCipher.fromBase64(MASTER_KEY, false)).findAll().forEach(s -> byId.put(s.webhook().id(), s));
        assertThat(byId.get("wh-slack").webhook().url()).isEqualTo(slackUrl);
        assertThat(byId.get("wh-slack").secret()).isNull();
        assertThat(byId.get("wh-hook").secret()).isEqualTo(secret);
        assertThat(byId.get("wh-hook").webhook().lastDeliveryStatus()).isEqualTo("OK");

        // raw columns: no plaintext secret, token-bearing URL path or query anywhere in the rows
        List<String> raw = jdbc.queryForList("select row_to_json(c)::text from connectors c", Map.of(), String.class);
        assertThat(String.join("\n", raw)).doesNotContain("SLACKTOKEN123").doesNotContain("QUERYTOKEN")
                .doesNotContain("SigningSecretValue").doesNotContain("/services/T000")
                .contains("https://hooks.slack.com/…").contains(cipher.keyId());
        byte[] ct = jdbc.queryForObject("select secret_ciphertext from connectors where id = 'wh-hook'", Map.of(), byte[].class);
        assertThat(new String(ct, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("whsec_");

        repo.delete("wh-slack");
        assertThat(repo.findAll()).extracting(s -> s.webhook().id()).containsExactly("wh-hook");
    }

    @Test
    void wrongMasterKeyOrMovedCiphertextFailsToDecrypt() {
        JdbcConnectorRepository repo = new JdbcConnectorRepository(jdbc, SecretCipher.fromBase64(MASTER_KEY, false));
        repo.insert(webhook("wh-a", "WEBHOOK", "https://a.example.com/x"), "whsec_a", "admin");
        repo.insert(webhook("wh-b", "WEBHOOK", "https://b.example.com/x"), "whsec_b", "admin");

        assertThatThrownBy(() -> new JdbcConnectorRepository(jdbc, SecretCipher.fromBase64(OTHER_KEY, false)).findAll())
                .isInstanceOf(PersistenceStartupException.class)
                .hasMessageContaining("cannot decrypt").hasMessageContaining("master key")
                .hasMessageNotContaining("whsec_");

        // same key id but forged: a wrong key that claims the right id fails on the GCM tag
        jdbc.update("update connectors set enc_key_id = :k", Map.of("k", SecretCipher.fromBase64(OTHER_KEY, false).keyId()));
        assertThatThrownBy(() -> new JdbcConnectorRepository(jdbc, SecretCipher.fromBase64(OTHER_KEY, false)).findAll())
                .isInstanceOf(PersistenceStartupException.class).hasMessageContaining("tag mismatch");
        jdbc.update("update connectors set enc_key_id = :k", Map.of("k", SecretCipher.fromBase64(MASTER_KEY, false).keyId()));
        assertThat(repo.findAll()).hasSize(2);

        // a ciphertext copied to another row does not decrypt (associated data binds id + column)
        jdbc.update("update connectors set secret_nonce = (select secret_nonce from connectors where id = 'wh-a'), "
                + "secret_ciphertext = (select secret_ciphertext from connectors where id = 'wh-a') where id = 'wh-b'", Map.of());
        assertThatThrownBy(repo::findAll).isInstanceOf(PersistenceStartupException.class).hasMessageContaining("wh-b");
    }

    // ---- rules --------------------------------------------------------------------------------------------------

    static RuleRepository.VersionRow v(int n, String src) {
        return new RuleRepository.VersionRow(n, src, Format.YAML, "sha256:" + n, "1.0." + n, T0.plusSeconds(n));
    }

    @Test
    void ruleVersionsAreImmutableAndConcurrentUpdatesConflict() {
        JdbcRuleRepository repo = new JdbcRuleRepository(jdbc, tx);
        RuleRepository.RuleRow saved = repo.save(new RuleRepository.RuleRow("r1", "api", false, List.of(v(1, "src-1")), T0, T0, null, 0));
        assertThat(saved.rowVersion()).isEqualTo(1);
        // stale writer (still holds row version 0): rejected
        assertThatThrownBy(() -> repo.save(new RuleRepository.RuleRow("r1", "api", true, List.of(v(1, "src-1")), T0, T0, null, 0)))
                .isInstanceOf(OptimisticLockException.class);

        RuleRepository.RuleRow v2 = repo.save(new RuleRepository.RuleRow("r1", "api", true, List.of(v(1, "src-1"), v(2, "src-2")),
                T0, T0.plusSeconds(9), null, saved.rowVersion()));
        assertThat(v2.rowVersion()).isEqualTo(2);
        assertThatThrownBy(() -> repo.save(new RuleRepository.RuleRow("r1", "api", false, List.of(v(1, "src-1"), v(2, "src-2")),
                T0, T0, null, saved.rowVersion()))).isInstanceOf(OptimisticLockException.class);

        // stored versions are never rewritten
        repo.save(new RuleRepository.RuleRow("r1", "api", true, List.of(v(1, "TAMPERED"), v(2, "src-2")), T0, T0, null, v2.rowVersion()));
        RuleRepository.RuleRow loaded = new JdbcRuleRepository(jdbc, tx).find("r1").orElseThrow();
        assertThat(loaded.enabled()).isTrue();
        assertThat(loaded.current().number()).isEqualTo(2);
        assertThat(loaded.versions()).extracting(RuleRepository.VersionRow::source).containsExactly("src-1", "src-2");
        assertThat(loaded.current().ruleVersion()).isEqualTo("1.0.2");

        // soft delete keeps the versions
        repo.save(new RuleRepository.RuleRow("r1", "api", false, loaded.versions(), T0, T0, T0.plusSeconds(60), loaded.rowVersion()));
        assertThat(repo.findAll()).singleElement().satisfies(r -> {
            assertThat(r.deleted()).isTrue();
            assertThat(r.versions()).hasSize(2);
        });
    }

    // ---- alerts -------------------------------------------------------------------------------------------------

    static Alert alert(String id, String dedup, List<String> events) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("kind", "AGGREGATION");
        evidence.put("value", new BigDecimal("2000000.000001"));
        evidence.put("window", Duration.ofMinutes(10));
        evidence.put("at", T0);
        evidence.put("count", 3);
        evidence.put("conditions", List.of(Map.of("field", "transaction.amount", "actual", new BigDecimal("12.50"))));
        return Alert.builder().id(id).ruleId("rule-a").ruleVersion("1.0.0").title("Rule A").severity(Severity.HIGH)
                .priority(Priority.P2).status(AlertStatus.DETECTED).evidenceLevel(EvidenceLevel.RISK_SIGNAL).network(Network.TRON)
                .groupKey("tron|TA").dedupKey(dedup).addEventIds(events).evidence(evidence).firstSeen(T0).lastSeen(T0)
                .createdAt(T0).updatedAt(T0).originChain(List.of("rule-x")).hop(1)
                .addHistory(new Alert.HistoryEntry(null, AlertStatus.DETECTED, "system", T0, "matched")).build();
    }

    @Test
    void alertRoundTripWithHistoryCommentsAndExactEvidence() {
        JdbcAlertRepository repo = new JdbcAlertRepository(jdbc, tx);
        Alert saved = repo.save(alert("a1", "k1", List.of("e1", "e2")));
        assertThat(saved.version()).isEqualTo(1);
        Alert updated = repo.save(saved.toBuilder().status(AlertStatus.PENDING).assignee("analyst").occurrenceCount(4)
                .addHistory(new Alert.HistoryEntry(AlertStatus.DETECTED, AlertStatus.PENDING, "system", T0.plusSeconds(1), "dispatch"))
                .addComment(new Alert.Comment("analyst", T0.plusSeconds(2), "<b>looked</b>")).escalationLevel(1)
                .lastEscalatedAt(T0.plusSeconds(3)).updatedAt(T0.plusSeconds(3)).build());
        assertThat(updated.version()).isEqualTo(2);

        Alert a = new JdbcAlertRepository(jdbc, tx).findById("a1").orElseThrow();
        assertThat(a.version()).isEqualTo(2);
        assertThat(a.status()).isEqualTo(AlertStatus.PENDING);
        assertThat(a.assignee()).isEqualTo("analyst");
        assertThat(a.occurrenceCount()).isEqualTo(4);
        assertThat(a.network()).isEqualTo(Network.TRON);
        assertThat(a.matchedEventIds()).containsExactly("e1", "e2");
        assertThat(a.originChain()).containsExactly("rule-x");
        assertThat(a.hop()).isEqualTo(1);
        assertThat(a.escalationLevel()).isEqualTo(1);
        assertThat(a.lastEscalatedAt()).isEqualTo(T0.plusSeconds(3));
        assertThat(a.history()).extracting(Alert.HistoryEntry::to).containsExactly(AlertStatus.DETECTED, AlertStatus.PENDING);
        assertThat(a.history().getFirst().from()).isNull();
        assertThat(a.comments()).singleElement().satisfies(c -> {
            assertThat(c.author()).isEqualTo("analyst");
            assertThat(c.text()).isEqualTo("<b>looked</b>");
        });
        assertThat(a.evidence()).isEqualTo(saved.evidence());
        assertThat(a.evidence().get("value")).isInstanceOf(BigDecimal.class).isEqualTo(new BigDecimal("2000000.000001"));
        assertThat(a.evidence().get("window")).isEqualTo(Duration.ofMinutes(10));

        repo.save(alert("a2", "k2", List.of("e3")));
        assertThat(repo.findAll()).extracting(Alert::id).containsExactly("a1", "a2");
        assertThat(repo.findByStatus(AlertStatus.PENDING)).extracting(Alert::id).containsExactly("a1");
        assertThat(repo.findByMatchedEventId("e2")).extracting(Alert::id).containsExactly("a1");
        assertThat(repo.findByMatchedEventId("nope")).isEmpty();
        assertThat(repo.findOpenByDedupKey("rule-a", "k1")).map(Alert::id).contains("a1");
        repo.save(a.toBuilder().status(AlertStatus.RESOLVED)
                .addHistory(new Alert.HistoryEntry(AlertStatus.PENDING, AlertStatus.RESOLVED, "analyst", T0.plusSeconds(9), null)).build());
        assertThat(repo.findOpenByDedupKey("rule-a", "k1")).isEmpty();
        assertThat(repo.findById("a1").orElseThrow().history()).hasSize(3);
    }

    @Test
    void staleAlertSaveIsRejected() {
        JdbcAlertRepository repo = new JdbcAlertRepository(jdbc, tx);
        Alert v1 = repo.save(alert("a1", "k1", List.of("e1")));
        repo.save(v1.toBuilder().assignee("first").build()); // another writer moves to version 2
        assertThatThrownBy(() -> repo.save(v1.toBuilder().assignee("second").build()))
                .isInstanceOf(ConcurrentAlertModificationException.class).hasMessageContaining("a1");
        assertThatThrownBy(() -> repo.save(alert("a1", "k1", List.of("e1")))) // duplicate insert
                .isInstanceOf(ConcurrentAlertModificationException.class);
        assertThat(repo.findById("a1").orElseThrow().assignee()).isEqualTo("first");
    }

    @Test
    void alertServiceOnJdbcIsReplayIdempotent() {
        AlertService service = new AlertService(new JdbcAlertRepository(jdbc, tx), Clock.systemUTC());
        RuleMatch m = new RuleMatch("rule-a", "1.0.0", "Rule A", Severity.HIGH, EvidenceLevel.ANOMALY, Network.TRON, "g", "k",
                "tron:0x1:0", T0, List.of("tron:0x1:0"), Map.of("kind", "STATELESS", "amount", new BigDecimal("5")), List.of(), 0);
        Alert a = service.onMatch(m, Duration.ofMinutes(5)).alert();
        service.transition(a.id(), AlertStatus.RESOLVED, "analyst", "ok");
        AlertService afterRestart = new AlertService(new JdbcAlertRepository(jdbc, tx), Clock.systemUTC());
        assertThat(afterRestart.onMatch(m, Duration.ofMinutes(5)).created()).isFalse();
        assertThat(afterRestart.repository().findAll()).hasSize(1);
    }

    // ---- deliveries (outbox) ------------------------------------------------------------------------------------

    static DeliveryRecord pending(String id, String alertId, Instant due) {
        Notification n = new Notification(id, ActionType.WEBHOOK, "wh-1", alertId, "rule-a", "1.0.0", "Rule A", Severity.HIGH,
                Priority.P2, EvidenceLevel.RISK_SIGNAL, Network.TRON, "g", "k", 1, T0, T0, List.of("e1"),
                Map.of("value", new BigDecimal("10.50"), "at", T0), Map.of("channel", "#soc"), List.of(), 0, T0);
        return new DeliveryRecord(id, alertId, "wh-1", n, DeliveryStatus.PENDING, 0, due, null, 0, T0, T0, null);
    }

    @Test
    void deliveryRoundTripAndClaimLease() {
        new JdbcAlertRepository(jdbc, tx).save(alert("a1", "k1", List.of("e1")));
        Clock clock = Clock.systemUTC();
        JdbcDeliveryStore a = new JdbcDeliveryStore(jdbc, "instance-a", Duration.ofMinutes(2), clock);
        JdbcDeliveryStore b = new JdbcDeliveryStore(jdbc, "instance-b", Duration.ofMinutes(2), clock);
        DeliveryRecord d1 = pending("d1", "a1", T0);
        a.save(d1);
        a.save(pending("d2", "a1", Instant.now().plusSeconds(3600))); // not due

        assertThat(b.find("d1")).contains(d1); // notification snapshot identical (exact decimals, instants)
        assertThat(a.findDue(Instant.now(), 10)).extracting(DeliveryRecord::id).containsExactly("d1");
        assertThat(b.findDue(Instant.now(), 10)).as("leased to instance-a").isEmpty();
        assertThat(jdbc.queryForObject("select claimed_by from deliveries where id = 'd1'", Map.of(), String.class)).isEqualTo("instance-a");

        // the attempt is recorded (retry later): the claim is released
        DeliveryRecord retry = new DeliveryRecord("d1", "a1", "wh-1", d1.notification(), DeliveryStatus.PENDING, 1, T0.plusSeconds(10),
                "HTTP 503", 503, T0, T0.plusSeconds(1), null);
        a.save(retry);
        assertThat(jdbc.queryForObject("select claimed_by from deliveries where id = 'd1'", Map.of(), String.class)).isNull();
        assertThat(b.findDue(Instant.now(), 10)).singleElement().satisfies(r -> {
            assertThat(r.attempts()).isEqualTo(1);
            assertThat(r.lastError()).isEqualTo("HTTP 503");
            assertThat(r.lastStatusCode()).isEqualTo(503);
        });

        b.save(new DeliveryRecord("d1", "a1", "wh-1", d1.notification(), DeliveryStatus.DEAD_LETTER, 8, null, "gone", 410, T0,
                T0.plusSeconds(2), null));
        assertThat(a.findByStatus(DeliveryStatus.DEAD_LETTER)).extracting(DeliveryRecord::id).containsExactly("d1");
        assertThat(a.findByAlert("a1")).extracting(DeliveryRecord::id).containsExactly("d1", "d2");
        assertThat(a.findDue(Instant.now(), 10)).isEmpty();
    }

    @Test
    void expiredClaimOfACrashedInstanceIsTakenOver() throws Exception {
        new JdbcAlertRepository(jdbc, tx).save(alert("a1", "k1", List.of("e1")));
        JdbcDeliveryStore crashed = new JdbcDeliveryStore(jdbc, "crashed", Duration.ofMillis(1), Clock.systemUTC());
        crashed.save(pending("d1", "a1", T0));
        assertThat(crashed.findDue(Instant.now(), 10)).hasSize(1);
        Thread.sleep(20);
        assertThat(new JdbcDeliveryStore(jdbc, "survivor", Duration.ofMinutes(2), Clock.systemUTC()).findDue(Instant.now(), 10))
                .extracting(DeliveryRecord::id).containsExactly("d1");
    }

    @Test
    void claimSkipsRowsLockedByAnotherTransaction() throws Exception {
        new JdbcAlertRepository(jdbc, tx).save(alert("a1", "k1", List.of("e1")));
        JdbcDeliveryStore store = new JdbcDeliveryStore(jdbc, "instance-b", Duration.ofMinutes(2), Clock.systemUTC());
        store.save(pending("d1", "a1", T0));
        store.save(pending("d2", "a1", T0.plusSeconds(1)));
        try (Connection other = ds.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement ps = other.prepareStatement("select id from deliveries where id = 'd1' for update")) {
                ps.executeQuery().close(); // instance A is in the middle of claiming d1
            }
            // instance B neither waits for the lock nor takes d1
            List<DeliveryRecord> claimed = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> store.findDue(Instant.now(), 10));
            assertThat(claimed).extracting(DeliveryRecord::id).containsExactly("d2");
            other.rollback();
        }
        assertThat(new JdbcDeliveryStore(jdbc, "instance-c", Duration.ofMinutes(2), Clock.systemUTC()).findDue(Instant.now(), 10))
                .extracting(DeliveryRecord::id).containsExactly("d1");
    }
}
