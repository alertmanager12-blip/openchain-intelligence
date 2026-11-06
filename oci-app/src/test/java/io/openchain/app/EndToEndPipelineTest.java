package io.openchain.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.openchain.alerting.pipeline.AlertingPipeline;
import io.openchain.app.chain.ChainIngestionService;
import io.openchain.app.chain.DemoLedgerProvider;
import io.openchain.alerting.notify.WebhookSignature;
import io.openchain.app.demo.DemoScenarios;
import io.openchain.blockchain.simulated.SimulatedChainProvider;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * Rule created through the API -> transfer published on the (simulated) chain -> BlockIndexer -> intelligence
 * (profile, detection, risk) -> enrichment -> rule engine -> alert -> outbox dispatch -> API-created webhook connector
 * (real WebhookConnector, signed) -> local sink at the OutboundHttp port.
 * Then: the same on-chain event again (no duplicate alert) and a second matching transfer within the cooldown
 * (grouped into the same alert, no second notification).
 */
class EndToEndPipelineTest extends ApiTestSupport {

    static final String RULE = """
            apiVersion: openchain.io/rule/v1
            id: e2e-pipeline-rule
            version: 1.0.0
            name: E2E large USDT transfer
            author: test-suite
            enabled: true
            scope:
              networks: [tron]
              eventTypes: [TOKEN_TRANSFER]
            conditions:
              logic: AND
              rules:
                - field: transaction.token.contract
                  operator: EQUALS
                  value: TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t
                - field: transaction.amount
                  operator: GREATER_THAN_OR_EQUAL
                  value: "777777"
            severity: HIGH
            cooldown: 1h
            dedupKey: "{{rule.id}}:{{event.transaction.from}}"
            actions:
              - type: WEBHOOK
                connectorId: WEBHOOK_ID
            """;

    @Autowired ChainIngestionService chains;
    @Autowired AlertingPipeline alerting;
    @Autowired StubHttp http;

    private ChainIngestionService.NetworkRuntime rt() { return chains.runtime(Network.TRON).orElseThrow(); }

    /** Publishes transfers in the next block(s), then mines past the confirmation depth and indexes. */
    private void publish(List<DemoLedgerProvider.Injection> transfers, int blocksBetween) {
        SimulatedChainProvider sim = rt().simulator().orElseThrow();
        DemoLedgerProvider ledger = rt().ledger().orElseThrow();
        long n = sim.head() + 1;
        for (DemoLedgerProvider.Injection i : transfers) {
            ledger.inject(n, i);
            n += blocksBetween;
        }
        sim.mine((int) (n - sim.head()) + rt().indexerConfig().confirmations() + 1);
        chains.poll(rt());
        alerting.dispatch(500);
    }

    private static BigInteger usdt(long whole) { return BigInteger.valueOf(whole).multiply(BigInteger.TEN.pow(6)); }

    private DemoLedgerProvider.Injection transfer(String seed, Address from, Address to, long amount) {
        return new DemoLedgerProvider.Injection(rt().ledger().orElseThrow().hashOf("e2e:" + seed), from, to, DemoScenarios.TRON_USDT, usdt(amount), null);
    }

    @Test
    void ruleToAlertToNotificationWithDedupAndCooldown() throws Exception {
        // webhook connector created through the API; its id is a slug a rule can reference
        String wh = mvc.perform(post("/api/v1/webhooks").header("X-API-Key", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"e2e sink\",\"url\":\"https://sink.e2e.example/hook\",\"events\":[\"alert.opened\"]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String webhookId = JsonPath.read(wh, "$.id");
        String secret = JsonPath.read(wh, "$.secret");
        assertThat(webhookId).matches("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$");
        mvc.perform(post("/api/v1/rules").header("X-API-Key", ANALYST).contentType("application/yaml").content(RULE.replace("WEBHOOK_ID", webhookId)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/rules/e2e-pipeline-rule/enable").header("X-API-Key", ANALYST)).andExpect(status().isOk());

        List<Address> pool = rt().simulator().orElseThrow().addressPool();
        Address sender = pool.get(7), recipient = pool.get(8);
        // a baseline of small transfers gives the detector a history to compare with
        List<DemoLedgerProvider.Injection> baseline = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) baseline.add(transfer("baseline-" + i, sender, pool.get(10 + i % 5), 100 + i));
        publish(baseline, 0);
        DemoLedgerProvider.Injection big = transfer("big-1", sender, recipient, 800_000);
        publish(List.of(big), 1);

        String list = mvc.perform(get("/api/v1/alerts?ruleId=e2e-pipeline-rule").header("X-API-Key", READER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        String alertId = JsonPath.read(list, "$.items[0].id");
        mvc.perform(get("/api/v1/alerts/" + alertId).header("X-API-Key", READER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txHash").value(big.txHash()))
                .andExpect(jsonPath("$.address").value(sender.value()))
                .andExpect(jsonPath("$.matchedEvent['transaction.amount']").value("800000.000000"))
                .andExpect(jsonPath("$.matchedEvent['wallet.risk_score']").exists())
                .andExpect(jsonPath("$.lifecycleStatus").value("NOTIFIED"))
                .andExpect(jsonPath("$.occurrenceCount").value(1));
        List<Integer> mine = deliveriesFor(alertId);
        assertThat(mine).hasSize(1);
        int k = mine.getFirst();
        assertThat(http.calls.get(k).toString()).isEqualTo("https://sink.e2e.example/hook");
        java.util.Map<String, String> h = http.headers.get(k);
        assertThat(h).containsEntry("X-OCI-Event", "alert.opened").containsKey("X-OCI-Delivery-Id");
        assertThat(WebhookSignature.verify(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), h.get("X-OCI-Signature"),
                http.bodies.get(k), java.time.Instant.now(), java.time.Duration.ofMinutes(5))).as("valid HMAC signature").isTrue();
        assertThat(WebhookSignature.verify("wrong-secret-wrong-secret-wrong-secret".getBytes(), h.get("X-OCI-Signature"),
                http.bodies.get(k), java.time.Instant.now(), java.time.Duration.ofMinutes(5))).isFalse();
        // detection + risk on the sender, for this very transaction
        mvc.perform(get("/api/v1/wallets/" + sender.value() + "/analysis?network=tron").header("X-API-Key", READER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signals[*].txHash", hasItem(big.txHash())))
                .andExpect(jsonPath("$.signals[*].type", hasItem("UNUSUAL_AMOUNT")))
                .andExpect(jsonPath("$.risk.score").isNumber());

        // the same on-chain event again (same tx hash and log index) -> deduplicated, no new alert
        publish(List.of(big), 1);
        mvc.perform(get("/api/v1/alerts?ruleId=e2e-pipeline-rule").header("X-API-Key", READER))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].occurrenceCount").value(1));

        // a second matching transfer from the same sender within the 1h cooldown -> grouped, not re-notified
        publish(List.of(transfer("big-2", sender, recipient, 900_000)), 1);
        mvc.perform(get("/api/v1/alerts?ruleId=e2e-pipeline-rule").header("X-API-Key", READER))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].occurrenceCount").value(2));
        assertThat(deliveriesFor(alertId)).hasSize(1);
    }

    /** Indexes of the sink deliveries whose JSON body carries this alert id. */
    private List<Integer> deliveriesFor(String alertId) {
        List<Integer> out = new java.util.ArrayList<>();
        for (int i = 0; i < http.bodies.size(); i++) {
            if (new String(http.bodies.get(i), java.nio.charset.StandardCharsets.UTF_8).contains(alertId)) out.add(i);
        }
        return out;
    }
}
