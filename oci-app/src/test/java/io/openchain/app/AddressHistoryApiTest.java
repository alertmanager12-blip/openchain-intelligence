package io.openchain.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.openchain.alerting.alert.AlertService;
import io.openchain.app.history.AddressHistoryService;
import io.openchain.blockchain.tron.TronAddress;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.history.AddressHistorySource;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wallet analysis exposes the on-demand history coverage (ADR-0020) additively; an analysed address that is not covered
 * starts an async backfill whose events reach profiles / detection / scoring but never raise alerts.
 */
class AddressHistoryApiTest extends ApiTestSupport {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String TARGET = TronAddress.fromHex("ab".repeat(20));
    static final String SENDER = TronAddress.fromHex("cd".repeat(20));
    static final String USDT = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";

    @Autowired AddressHistoryService history;
    @Autowired AlertService alerts;

    /** Two historical USDT receipts of TARGET, no network call. */
    static final class Fixture implements AddressHistorySource {
        final List<String> requested = new CopyOnWriteArrayList<>();
        @Override public String sourceId() { return "trongrid"; }
        @Override public Network network() { return Network.TRON; }
        @Override public Coverage coverage() { return new Coverage(true, false, true, false); }
        @Override public long historySafeHead() { return 1_000_000; }
        @Override public HistoryPage fetch(Address address, long from, long to, String cursor) {
            requested.add(address.value());
            List<ChainEvent> events = List.of(receipt(1, "5000000"), receipt(2, "7500000000"));
            return new HistoryPage(events, from, Instant.parse("2026-01-01T00:00:00Z"), null, 2);
        }
        static ChainEvent receipt(int i, String raw) {
            BigDecimal amount = new BigDecimal(raw).movePointLeft(6);
            return new ChainEvent("tron:hist" + i + ":trc20:0:0", ChainEvent.EventType.TOKEN_TRANSFER, Network.TRON, -1, null,
                    Instant.parse("2025-12-0" + i + "T00:00:00Z"), Instant.now(), false, Map.of("transaction.hash", "hist" + i,
                    "transaction.from", SENDER, "transaction.to", TARGET, "transaction.amount", amount,
                    "transaction.amount_raw", new BigDecimal(raw), "transaction.token.symbol", "USDT",
                    "transaction.token.contract", USDT, "transaction.token.decimals", 6L));
        }
    }

    JsonNode analysis() throws Exception {
        String body = mvc.perform(get("/api/v1/wallets/" + TARGET + "/analysis?network=tron").header("X-API-Key", ANALYST))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(body);
    }

    @Test void coverageIsReportedAndHistoryNeverRaisesAlerts() throws Exception {
        JsonNode before = analysis();
        assertThat(before.get("history").get("status").asString()).isEqualTo("NOT_CONFIGURED");
        assertThat(before.get("history").get("message").asString()).startsWith("Simulated demo chain");
        assertThat(before.get("txCount").asLong()).isZero();

        int alertsBefore = alerts.repository().findAll().size();
        Fixture source = new Fixture();
        history.register(source, new AddressHistoryService.Config(1_000, 10, 100, Duration.ZERO));
        try {
            JsonNode h = analysis().get("history");
            assertThat(h.get("status").asString()).isIn("PENDING", "RUNNING", "COMPLETE");
            long deadline = System.currentTimeMillis() + 10_000;
            JsonNode after = analysis();
            while (!after.get("history").get("status").asString().equals("COMPLETE") && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
                after = analysis();
            }
            System.out.println("COVERAGE JSON: " + after.get("history"));
            JsonNode cov = after.get("history");
            assertThat(cov.get("status").asString()).isEqualTo("COMPLETE");
            assertThat(cov.get("sources").get(0).asString()).isEqualTo("trongrid");
            assertThat(cov.get("eventsFound").asLong()).isEqualTo(2);
            assertThat(cov.get("nativeCovered").asBoolean()).isTrue();
            assertThat(cov.get("internalCovered").asBoolean()).isFalse();
            assertThat(cov.get("truncated").asBoolean()).isFalse();
            assertThat(cov.get("message").asString()).startsWith("History: TRC-20 transfers and native transfers since 2026-01-01 (trongrid, 2 event(s)).")
                    .contains("Internal transfers and approvals are not covered.");
            // the history reached profiles and flows ...
            assertThat(after.get("txCount").asLong()).isEqualTo(2);
            assertThat(after.get("flows").get(0).get("rawIn").asString()).isEqualTo("7505000000");
            // ... and is idempotent: analysing again does not fetch again
            analysis();
            assertThat(source.requested).containsOnly(TARGET).hasSize(1);
            // ... but raised no alert (rules are not evaluated for history)
            assertThat(alerts.repository().findAll()).hasSize(alertsBefore);
        } finally {
            history.unregister(Network.TRON);
            history.markSimulated(Network.TRON);
        }
    }
}
