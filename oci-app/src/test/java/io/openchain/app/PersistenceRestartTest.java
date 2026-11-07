package io.openchain.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.openchain.alerting.alert.Alert;
import io.openchain.alerting.alert.AlertService;
import io.openchain.alerting.alert.AlertSubject;
import io.openchain.core.event.ChainEvent;
import io.openchain.alerting.alert.AlertStatus;
import io.openchain.alerting.engine.RuleMatch;
import io.openchain.alerting.notify.WebhookSignature;
import io.openchain.alerting.notify.delivery.DeliveryRecord;
import io.openchain.alerting.notify.delivery.DeliveryService;
import io.openchain.alerting.notify.delivery.DeliveryStatus;
import io.openchain.alerting.notify.delivery.DeliveryStore;
import io.openchain.alerting.pipeline.AlertingPipeline;
import io.openchain.alerting.rule.ActionType;
import io.openchain.alerting.rule.Rule;
import io.openchain.app.persistence.PersistenceMode;
import io.openchain.app.persistence.PersistenceStartupException;
import io.openchain.app.persistence.PostgresSupport;
import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.model.Network;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Restart survival against PostgreSQL 17: operator state written through the API in a first application context is
 * still there, and still works, in a second context started on the same database. Both contexts run the real
 * application (demo mode with a simulated chain, as in {@link ApiTestSupport}) on a random port.
 */
@PostgresSupport.RequiresDocker
class PersistenceRestartTest {

    static final String MASTER_KEY = Base64.getEncoder().encodeToString("restart-test-master-key-32bytes!".getBytes(StandardCharsets.UTF_8));
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    static final String RULE = """
            apiVersion: openchain.io/rule/v1
            id: restart-survival
            version: 1.2.0
            name: Restart survival rule
            author: test-suite
            enabled: false
            scope:
              networks: [tron]
              eventTypes: [TOKEN_TRANSFER]
            conditions:
              logic: AND
              rules:
                - field: transaction.amount
                  operator: GREATER_THAN_OR_EQUAL
                  value: "123456789"
            severity: HIGH
            evidenceLevel: ANOMALY
            cooldown: 10m
            documentation: persistence test rule
            actions:
              - type: WEBHOOK
                connectorId: %s
            examples:
              - name: big
                expect: MATCH
                event: { fields: { transaction.amount: "200000000" } }
            """;

    record Res(int status, JsonNode body) {}

    static ConfigurableApplicationContext start(PostgresSupport.Database db, Map<String, Object> extra) {
        Map<String, Object> p = new HashMap<>();
        p.put("spring.datasource.url", db.url());
        p.put("spring.datasource.username", db.username());
        p.put("spring.datasource.password", db.password());
        p.put("oci.security.crypto-master-key", MASTER_KEY);
        p.put("server.port", "0");
        p.putAll(extra);
        // command-line arguments: they override application*.yml (default properties would not)
        String[] args = p.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
        return new SpringApplicationBuilder(OciApplication.class, ApiTestSupport.TestBeans.class).profiles("test").run(args);
    }

    static Res call(ConfigurableApplicationContext ctx, String method, String path, String key, String json) throws Exception {
        int port = Integer.parseInt(ctx.getEnvironment().getProperty("local.server.port"));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("X-API-Key", key);
        b = json == null ? b.method(method, HttpRequest.BodyPublishers.noBody())
                : b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body() == null || r.body().isBlank() ? null : JSON.readTree(r.body()));
    }

    static String json(Object o) { return JSON.writeValueAsString(o); }

    static long count(NamedParameterJdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("select count(*) from " + table, Map.of(), Long.class);
    }

    @Test
    void operatorStateSurvivesARestart() throws Exception {
        PostgresSupport.Database db = PostgresSupport.newDatabase();
        String key, webhookId, webhookSecret, alertId, deliveryId, healedId;
        long alertsBefore, keysBefore, rulesBefore;
        String webhookUrl = "https://hooks.example.com/oci/restart?token=URLTOKEN42";

        try (ConfigurableApplicationContext ctx = start(db, Map.of())) {
            assertThat(ctx.getBean(PersistenceMode.class).jdbc()).isTrue();

            // API key
            Res k = call(ctx, "POST", "/api/v1/api-keys", ApiTestSupport.ADMIN,
                    json(Map.of("name", "restart-ci", "scopes", List.of("alerts:read", "alerts:write", "rules:read", "webhooks:read"))));
            assertThat(k.status()).isEqualTo(201);
            key = k.body().get("secret").asString();

            // connector (signing secret shown once)
            Res w = call(ctx, "POST", "/api/v1/webhooks", ApiTestSupport.ADMIN,
                    json(Map.of("name", "soc", "url", webhookUrl, "events", List.of("alert.opened"))));
            assertThat(w.status()).isEqualTo(201);
            webhookId = w.body().get("id").asString();
            webhookSecret = w.body().get("secret").asString();

            // rule, then enable it
            Res r = call(ctx, "POST", "/api/v1/rules", ApiTestSupport.ADMIN, json(Map.of("source", RULE.formatted(webhookId))));
            assertThat(r.status()).isEqualTo(201);
            assertThat(call(ctx, "POST", "/api/v1/rules/restart-survival/enable", ApiTestSupport.ADMIN, null).status()).isEqualTo(200);

            // an alert for that rule with a delivery to the connector (as the pipeline does), first attempt fails (503)
            AlertService alerts = ctx.getBean(AlertService.class);
            // the triggering event is NOT part of the demo chain: after the restart nothing re-indexes it, so its wallet and
            // transaction can only come from what was stored with the alert (V3 subject columns)
            ChainEvent trigger = new ChainEvent("tron:0xrestart:0", ChainEvent.EventType.TOKEN_TRANSFER, Network.TRON, 4242, "0xblk",
                    Instant.parse("2026-11-01T10:00:00Z"), Instant.now(), false, Map.of("transaction.hash", "0xrestart",
                    "transaction.from", "TRestartSender0000000000000000001", "transaction.to", "TRestartRecipient000000000000002",
                    "wallet.address", "TRestartSender0000000000000000001", "transaction.amount", new BigDecimal("1250.5"),
                    "transaction.token.symbol", "USDT"));
            RuleMatch m = new RuleMatch("restart-survival", "1.2.0", "Restart survival rule", Severity.HIGH, EvidenceLevel.ANOMALY,
                    Network.TRON, "g", "restart-key", "tron:0xrestart:0", Instant.now(), List.of("tron:0xrestart:0"),
                    Map.of("kind", "STATELESS", "amount", new BigDecimal("123456789.5")), List.of(), 0, AlertSubject.of(trigger));
            Alert a = alerts.onMatch(m, Duration.ofMinutes(10)).alert();
            alertId = a.id();
            a = alerts.transition(alertId, AlertStatus.PENDING, AlertService.SYSTEM, "dispatching 1 action(s)");
            DeliveryRecord d = ctx.getBean(DeliveryService.class).enqueue(a, new Rule.ActionSpec(ActionType.WEBHOOK, webhookId, Map.of()));
            deliveryId = d.id();
            ApiTestSupport.StubHttp stub = ctx.getBean(ApiTestSupport.StubHttp.class);
            stub.status = 503;
            ctx.getBean(AlertingPipeline.class).dispatch(100);
            DeliveryRecord afterFailure = ctx.getBean(DeliveryStore.class).find(deliveryId).orElseThrow();
            assertThat(afterFailure.status()).isEqualTo(DeliveryStatus.PENDING);
            assertThat(afterFailure.attempts()).isEqualTo(1);

            // analyst work: acknowledge + comment (with the new key)
            assertThat(call(ctx, "POST", "/api/v1/alerts/" + alertId + "/acknowledge", key, null).status()).isEqualTo(200);
            assertThat(call(ctx, "POST", "/api/v1/alerts/" + alertId + "/comments", key, json(Map.of("body", "checked before restart")))
                    .status()).isEqualTo(200);

            NamedParameterJdbcTemplate jdbc = ctx.getBean(NamedParameterJdbcTemplate.class);
            // an alert stored before V3 (no subject): blanked here, it must get its subject back when the demo chain
            // re-indexes its triggering event after the restart
            healedId = jdbc.queryForObject("select id from alerts where rule_id = 'wallet-draining' order by created_at, id limit 1",
                    Map.of(), String.class);
            jdbc.update("update alerts set subject_event_id = null, subject_event_type = null, subject_address = null, "
                    + "subject_counterparty = null, subject_tx_hash = null, subject_block = null, subject_time = null, "
                    + "subject_fields = null where id = :id", Map.of("id", healedId));
            alertsBefore = count(jdbc, "alerts");
            assertThat(alertsBefore).as("demo chain alerts + the test alert").isGreaterThan(1);
            keysBefore = count(jdbc, "api_keys");
            rulesBefore = count(jdbc, "rules");
            String rawConnector = jdbc.queryForObject("select row_to_json(c)::text from connectors c", Map.of(), String.class);
            assertThat(rawConnector).doesNotContain(webhookSecret).doesNotContain("URLTOKEN42").doesNotContain("/oci/restart");
            String rawKeys = String.join("\n", jdbc.queryForList("select row_to_json(k)::text from api_keys k", Map.of(), String.class));
            assertThat(rawKeys).doesNotContain(key.substring(key.lastIndexOf('_') + 1));
        }

        try (ConfigurableApplicationContext ctx = start(db, Map.of())) {
            // the key created before the restart still authenticates (stable pepper, hash persisted)
            Res alert = call(ctx, "GET", "/api/v1/alerts/" + alertId, key, null);
            assertThat(alert.status()).isEqualTo(200);
            assertThat(alert.body().get("status").asString()).isEqualTo("ACKNOWLEDGED");
            assertThat(alert.body().get("comments").get(0).get("body").asString()).isEqualTo("checked before restart");
            List<String> actions = alert.body().get("history").valueStream().map(h -> h.get("action").asString()).toList();
            assertThat(actions).contains("DETECTED", "PENDING", "ACKNOWLEDGED");
            // wallet / transaction / matched event survive the restart (stored subject, not the in-memory event history)
            System.out.println("ALERT AFTER RESTART: " + alert.body().toString());
            assertThat(alert.body().get("address").asString()).isEqualTo("TRestartSender0000000000000000001");
            assertThat(alert.body().get("txHash").asString()).isEqualTo("0xrestart");
            assertThat(alert.body().get("counterparty").asString()).isEqualTo("TRestartRecipient000000000000002");
            assertThat(alert.body().get("blockNumber").asLong()).isEqualTo(4242);
            assertThat(alert.body().get("matchedEvent").get("transaction.amount").asString()).isEqualTo("1250.5");
            assertThat(alert.body().get("evidenceSummary").asString()).isEqualTo("TResta…0001 sent 1,250.5 USDT to TResta…0002.");
            Res filtered = call(ctx, "GET", "/api/v1/alerts?address=TRestartSender0000000000000000001", key, null);
            assertThat(filtered.body().toString()).contains(alertId);
            // every alert of the re-indexed demo chain has its wallet, including the one blanked before the restart
            Res all = call(ctx, "GET", "/api/v1/alerts?limit=200", key, null);
            List<JsonNode> listed = (all.body().has("items") ? all.body().get("items") : all.body()).valueStream().toList();
            assertThat(listed).isNotEmpty().allSatisfy(x -> assertThat(x.get("address").isNull()).as(x.get("id").asString()).isFalse());
            Res healed = call(ctx, "GET", "/api/v1/alerts/" + healedId, key, null);
            assertThat(healed.body().get("txHash").isNull()).isFalse();
            assertThat(healed.body().get("evidenceSummary").asString()).containsPattern("sent .*balance.* within 2 minutes");
            assertThat(ctx.getBean(NamedParameterJdbcTemplate.class).queryForObject(
                    "select subject_tx_hash from alerts where id = :id", Map.of("id", healedId), String.class)).isNotNull();

            // connector listed, masked, and its decrypted secret signs the retried delivery
            Res hooks = call(ctx, "GET", "/api/v1/webhooks", key, null);
            assertThat(hooks.status()).isEqualTo(200);
            JsonNode hook = hooks.body().valueStream().filter(h -> h.get("id").asString().equals(webhookId)).findFirst().orElseThrow();
            assertThat(hook.get("url").asString()).isEqualTo("https://hooks.example.com/…");
            assertThat(hook.has("secret")).isFalse();
            assertThat(hooks.body().toString()).doesNotContain("URLTOKEN42");

            // rule: still enabled, same version
            Res rule = call(ctx, "GET", "/api/v1/rules/restart-survival", key, null);
            assertThat(rule.status()).isEqualTo(200);
            assertThat(rule.body().get("enabled").asBoolean()).isTrue();
            assertThat(rule.body().get("version").asInt()).isEqualTo(1);
            assertThat(rule.body().get("ruleVersion").asString()).isEqualTo("1.2.0");
            assertThat(rule.body().get("origin").asString()).isEqualTo("api");

            // the pending delivery is retried by the new process (made due now instead of waiting for the backoff)
            NamedParameterJdbcTemplate jdbc = ctx.getBean(NamedParameterJdbcTemplate.class);
            jdbc.update("update deliveries set next_attempt_at = now() where id = :id", Map.of("id", deliveryId));
            ApiTestSupport.StubHttp stub = ctx.getBean(ApiTestSupport.StubHttp.class);
            ctx.getBean(AlertingPipeline.class).dispatch(100);
            DeliveryRecord delivered = ctx.getBean(DeliveryStore.class).find(deliveryId).orElseThrow();
            assertThat(delivered.status()).isEqualTo(DeliveryStatus.DELIVERED);
            assertThat(delivered.attempts()).isEqualTo(2);
            int i = stub.calls.indexOf(URI.create(webhookUrl));
            assertThat(i).as("delivery sent to the full (decrypted) URL").isGreaterThanOrEqualTo(0);
            assertThat(stub.headers.get(i).get(WebhookSignature.IDEMPOTENCY_HEADER)).isEqualTo(deliveryId);
            assertThat(WebhookSignature.verify(webhookSecret.getBytes(StandardCharsets.UTF_8), stub.headers.get(i).get(WebhookSignature.HEADER),
                    stub.bodies.get(i), Instant.now(), Duration.ofMinutes(5))).as("signed with the secret created before the restart").isTrue();

            // demo scenarios still fully evidenced (notifications of alerts raised before the restart come from the outbox)
            Res scen = call(ctx, "GET", "/api/v1/demo/scenarios", ApiTestSupport.ADMIN, null);
            assertThat(scen.status()).isEqualTo(200);
            List<JsonNode> items = scen.body().get("items").valueStream().toList();
            assertThat(items).hasSize(6).allSatisfy(it -> assertThat(it.get("checks").toString()).doesNotContain("false"));

            // idempotent bootstrap: no duplicated alerts (re-indexed demo chain), keys or rules
            assertThat(count(jdbc, "alerts")).isEqualTo(alertsBefore);
            assertThat(count(jdbc, "api_keys")).isEqualTo(keysBefore);
            assertThat(count(jdbc, "rules")).isEqualTo(rulesBefore);
        }
    }

    @Test
    void jdbcModeFailsFastWithoutMasterKeyOrPepper() {
        PostgresSupport.Database db = PostgresSupport.newDatabase();
        assertThatThrownBy(() -> start(db, Map.of("oci.security.crypto-master-key", "")).close())
                .hasRootCauseInstanceOf(PersistenceStartupException.class)
                .rootCause().hasMessageContaining("OCI_CRYPTO_MASTER_KEY is required");
        assertThatThrownBy(() -> start(db, Map.of("oci.demo.enabled", "false", "oci.security.api-key-pepper", "")).close())
                .hasRootCauseInstanceOf(PersistenceStartupException.class)
                .rootCause().hasMessageContaining("OCI_SECURITY_API_KEY_PEPPER is required");
    }
}
