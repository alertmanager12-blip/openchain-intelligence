package io.openchain.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.openchain.alerting.pipeline.AlertingPipeline;
import io.openchain.app.chain.ChainIngestionService;
import io.openchain.app.chain.DemoLedgerProvider;
import io.openchain.app.chain.EventHistory;
import io.openchain.app.demo.DemoScenarios;
import io.openchain.blockchain.simulated.SimulatedChainProvider;
import io.openchain.blockchain.tron.TronAddress;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.TokenFreeze;
import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * Simulated issuer freeze -> BlockIndexer (STABLECOIN_FREEZE) -> intelligence (ISSUER_FROZEN label, rescore,
 * counterparty exposure) -> enrichment (freeze.known_counterparties, wallet.*) -> API-created rule -> alert -> signed
 * webhook delivery. Then the unfreeze delists and rescores the address. Also: native transfers and approvals reach
 * the rule engine, and the field catalog reports the new fields as populated.
 */
class FreezeToAlertPipelineTest extends ApiTestSupport {

    static final String RULE = """
            apiVersion: openchain.io/rule/v1
            id: e2e-freeze-rule
            version: 1.0.0
            name: E2E issuer freeze of a counterparty
            author: test-suite
            enabled: true
            scope:
              networks: [tron]
              eventTypes: [STABLECOIN_FREEZE]
            conditions:
              logic: AND
              rules:
                - field: freeze.action
                  operator: EQUALS
                  value: FREEZE
                - field: freeze.known_counterparties
                  operator: GREATER_THAN
                  value: 0
                - field: wallet.labels
                  operator: CONTAINS
                  value: issuer-frozen
            severity: HIGH
            evidenceLevel: CONFIRMED_BY_SOURCE
            cooldown: 1h
            dedupKey: "{{rule.id}}:{{event.freeze.address}}"
            actions:
              - type: WEBHOOK
                connectorId: WEBHOOK_ID
            """;

    @Autowired ChainIngestionService chains;
    @Autowired AlertingPipeline alerting;
    @Autowired EventHistory history;
    @Autowired StubHttp http;

    private ChainIngestionService.NetworkRuntime rt() { return chains.runtime(Network.TRON).orElseThrow(); }

    private void mineAndIndex(long lastBlock) {
        SimulatedChainProvider sim = rt().simulator().orElseThrow();
        sim.mine((int) (lastBlock - sim.head()) + rt().indexerConfig().confirmations() + 1);
        chains.poll(rt());
        alerting.dispatch(500);
    }

    static Address fresh(String seed) throws Exception {
        byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(("freeze-e2e:" + seed).getBytes());
        return new Address(Network.TRON, TronAddress.fromHex(HexFormat.of().formatHex(h).substring(0, 40)));
    }

    @Test
    void simulatedFreezeRaisesARuleAlertAndUnfreezeDelists() throws Exception {
        String wh = mvc.perform(post("/api/v1/webhooks").header("X-API-Key", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"freeze sink\",\"url\":\"https://sink.freeze.example/hook\",\"events\":[\"alert.opened\"]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/v1/rules").header("X-API-Key", ANALYST).contentType("application/yaml")
                .content(RULE.replace("WEBHOOK_ID", (String) JsonPath.read(wh, "$.id")))).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/rules/e2e-freeze-rule/enable").header("X-API-Key", ANALYST)).andExpect(status().isOk());

        DemoLedgerProvider ledger = rt().ledger().orElseThrow();
        SimulatedChainProvider sim = rt().simulator().orElseThrow();
        Address payer = fresh("payer"), frozen = fresh("frozen");
        long n = sim.head() + 1;
        ledger.inject(n, new DemoLedgerProvider.Injection(ledger.hashOf("fz:fund"), ledger.faucet(), payer, DemoScenarios.TRON_USDT,
                BigInteger.valueOf(10_000_000_000L), null));
        ledger.inject(n + 1, new DemoLedgerProvider.Injection(ledger.hashOf("fz:pay"), payer, frozen, DemoScenarios.TRON_USDT,
                BigInteger.valueOf(3_000_000_000L), null));
        String freezeTx = ledger.hashOf("fz:freeze");
        ledger.injectFreeze(n + 2, new DemoLedgerProvider.FreezeInjection(freezeTx, frozen, DemoScenarios.TRON_USDT, "tether",
                TokenFreeze.Action.FREEZE, "AddedBlackList"));
        mineAndIndex(n + 2);

        String list = mvc.perform(get("/api/v1/alerts?ruleId=e2e-freeze-rule").header("X-API-Key", READER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        String alertId = JsonPath.read(list, "$.items[0].id");
        mvc.perform(get("/api/v1/alerts/" + alertId).header("X-API-Key", READER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txHash").value(freezeTx))
                .andExpect(jsonPath("$.matchedEvent['freeze.address']").value(frozen.value()))
                .andExpect(jsonPath("$.matchedEvent['freeze.token']").value("USDT"))
                .andExpect(jsonPath("$.matchedEvent['freeze.issuer']").value("tether"))
                .andExpect(jsonPath("$.matchedEvent['freeze.known_counterparties']").value(1))
                .andExpect(jsonPath("$.matchedEvent['wallet.risk_score']").value(60))
                .andExpect(jsonPath("$.lifecycleStatus").value("NOTIFIED"));
        assertThat(http.bodies.stream().map(b -> new String(b, java.nio.charset.StandardCharsets.UTF_8)))
                .anyMatch(b -> b.contains(alertId));

        // label (authoritative, on-chain source) + risk of the frozen address; exposure of the payer
        mvc.perform(get("/api/v1/wallets/" + frozen.value() + "/analysis?network=tron").header("X-API-Key", READER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.labels[*].source", hasItem("tether-onchain")))
                .andExpect(jsonPath("$.labels[*].reference", hasItem(freezeTx)))
                .andExpect(jsonPath("$.risk.score").value(60));
        mvc.perform(get("/api/v1/wallets/" + payer.value() + "/analysis?network=tron").header("X-API-Key", READER))
                .andExpect(jsonPath("$.signals[*].type", hasItem("FLAGGED_EXPOSURE")));

        // unfreeze -> delisted and rescored; no new alert
        long m = sim.head() + 1;
        ledger.injectFreeze(m, new DemoLedgerProvider.FreezeInjection(ledger.hashOf("fz:unfreeze"), frozen, DemoScenarios.TRON_USDT,
                "tether", TokenFreeze.Action.UNFREEZE, "RemovedBlackList"));
        mineAndIndex(m);
        mvc.perform(get("/api/v1/wallets/" + frozen.value() + "/analysis?network=tron").header("X-API-Key", READER))
                .andExpect(jsonPath("$.labels[*].source", not(hasItem("tether-onchain"))))
                .andExpect(jsonPath("$.risk.score").value(0));
        mvc.perform(get("/api/v1/alerts?ruleId=e2e-freeze-rule").header("X-API-Key", READER)).andExpect(jsonPath("$.items.length()").value(1));
        assertThat(history.range(null, null, Network.TRON, 1_000_000)).anyMatch(e -> e.type() == ChainEvent.EventType.STABLECOIN_UNFREEZE
                && frozen.value().equals(e.field("freeze.address")));
    }

    @Test
    void nativeTransfersAndApprovalsReachTheRuleEngineAndFieldsAreAvailable() throws Exception {
        List<ChainEvent> events = history.range(null, null, Network.TRON, 1_000_000);
        assertThat(events).anyMatch(e -> e.type() == ChainEvent.EventType.NATIVE_TRANSFER && "TRX".equals(e.field("transaction.token.symbol"))
                && e.field("wallet.balance") != null && e.eventId().endsWith(":native"));
        assertThat(events).anyMatch(e -> e.type() == ChainEvent.EventType.APPROVAL && Boolean.TRUE.equals(e.field("approval.unlimited"))
                && e.field("counterparty.address") != null);
        mvc.perform(get("/api/v1/rules/fields").header("X-API-Key", READER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.path == 'approval.owner')].available", hasItem(true)))
                .andExpect(jsonPath("$[?(@.path == 'approval.spender')].available", hasItem(true)))
                .andExpect(jsonPath("$[?(@.path == 'approval.amount')].available", hasItem(true)))
                .andExpect(jsonPath("$[?(@.path == 'approval.unlimited')].available", hasItem(true)))
                .andExpect(jsonPath("$[?(@.path == 'approval.spender_is_contract')].available", hasItem(true)))
                .andExpect(jsonPath("$[?(@.path == 'approval.spender_verified')].available", hasItem(false)))
                .andExpect(jsonPath("$[?(@.path == 'freeze.address')].available", hasItem(true)))
                .andExpect(jsonPath("$[?(@.path == 'freeze.action')].available", hasItem(true)))
                .andExpect(jsonPath("$[?(@.path == 'freeze.action')].sourceBacked", hasItem(true)))
                .andExpect(jsonPath("$[?(@.path == 'freeze.known_counterparties')].available", hasItem(true)));
        mvc.perform(get("/api/v1/rules/event-types").header("X-API-Key", READER))
                .andExpect(jsonPath("$[?(@.type == 'NATIVE_TRANSFER')].available", hasItem(true)))
                .andExpect(jsonPath("$[?(@.type == 'APPROVAL')].available", hasItem(true)))
                .andExpect(jsonPath("$[?(@.type == 'STABLECOIN_FREEZE')].available", hasItem(true)))
                .andExpect(jsonPath("$[?(@.type == 'CONTRACT_ADMIN_CHANGE')].available", hasItem(true)));
    }

    @Test
    void demoScenarioFAndTheUnlimitedApprovalToAListedSpender() throws Exception {
        String r = mvc.perform(get("/api/v1/demo/scenarios").header("X-API-Key", READER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[5].id").value("F"))
                .andExpect(jsonPath("$.items[5].checks.expectedRuleTriggered").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(r, "$.items[5].events[?(@.type == 'STABLECOIN_FREEZE')]")).isNotEmpty();
        assertThat((List<?>) JsonPath.read(r, "$.items[5].events[?(@.type == 'STABLECOIN_UNFREEZE')]")).isNotEmpty();
        // supplementary demo data: unlimited USDT approval to the demo-scam-listed pool address
        assertThat(history.range(null, null, Network.TRON, 1_000_000)).anyMatch(e -> e.type() == ChainEvent.EventType.DETECTION_SIGNAL
                && "UNUSUAL_APPROVAL".equals(e.field("signal.type")) && "HIGH".equals(e.field("signal.severity")));
    }
}
