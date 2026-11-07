package io.openchain.app.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.alerting.alert.Alert;
import io.openchain.alerting.alert.AlertSubject;
import io.openchain.alerting.alert.Priority;
import io.openchain.core.detection.Severity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** One readable line per alert kind, from data stored with the alert only. */
class AlertNarrativeTest {

    static final String W = "0x1111111111111111111111111111111111111111", C = "0x2222222222222222222222222222222222222222";

    static Alert alert(Map<String, Object> evidence, Map<String, Object> fields, long occurrences) {
        return Alert.builder().id("a").ruleId("r").severity(Severity.HIGH).priority(Priority.P2).evidence(evidence)
                .occurrenceCount(occurrences).createdAt(Instant.EPOCH).updatedAt(Instant.EPOCH)
                .subject(new AlertSubject("e", "TOKEN_TRANSFER", W, C, "0xt", 1L, Instant.EPOCH, fields)).build();
    }

    @Test void aggregations() {
        Map<String, Object> f = Map.of("transaction.token.symbol", "USDT");
        assertThat(AlertNarrative.explain(alert(Map.of("kind", "AGGREGATION", "function", "RATIO_OF_BALANCE", "value", "0.92",
                "window", "PT2M", "eventCount", 3), f, 1), f))
                .isEqualTo("0x1111…1111 sent 92% of its USDT balance within 2 minutes (3 transfers).");
        assertThat(AlertNarrative.explain(alert(Map.of("kind", "AGGREGATION", "function", "RATIO_OF_BALANCE", "value", "3.99",
                "window", "PT2M", "eventCount", 13), f, 1), f))
                .isEqualTo("0x1111…1111 sent more than its whole starting USDT balance (3.99 times it, funds received meanwhile "
                        + "were passed on) within 2 minutes (13 transfers).");
        assertThat(AlertNarrative.explain(alert(Map.of("kind", "AGGREGATION", "function", "SUM", "value", "150000", "threshold", "100000",
                "window", "PT1H", "eventCount", 4), f, 2), f))
                .isEqualTo("0x1111…1111 moved 150,000 USDT in total within 1 hour (4 transfers) (threshold 100,000 USDT). "
                        + "Seen 2 times since this alert opened.");
        assertThat(AlertNarrative.explain(alert(Map.of("kind", "AGGREGATION", "function", "DISTINCT_COUNT", "field", "transaction.to",
                "value", "12", "threshold", "10", "window", "PT3M"), f, 1), f))
                .isEqualTo("0x1111…1111 reached 12 distinct recipients within 3 minutes (threshold 10).");
    }

    @Test void singleEvents() {
        Map<String, Object> transfer = Map.of("transaction.from", W, "transaction.to", C, "transaction.amount", new BigDecimal("2500000"),
                "transaction.token.symbol", "USDT");
        assertThat(AlertNarrative.explain(alert(Map.of("kind", "STATELESS"), transfer, 1), transfer))
                .isEqualTo("0x1111…1111 sent 2,500,000 USDT to 0x2222…2222.");
        Map<String, Object> signal = Map.of("signal.type", "SANCTIONED_EXPOSURE", "signal.title", "Direct exposure to a sanctioned address",
                "signal.summary", "Received 12,500 USDT directly from an address listed as sanctioned by ofac-sdn.", "wallet.address", W);
        assertThat(AlertNarrative.explain(alert(Map.of("kind", "STATELESS"), signal, 1), signal))
                .isEqualTo("Received 12,500 USDT directly from an address listed as sanctioned by ofac-sdn "
                        + "(Direct exposure to a sanctioned address detected on 0x1111…1111).");
        Map<String, Object> freeze = Map.of("freeze.address", W, "freeze.issuer", "tether", "freeze.token", "USDT", "freeze.action", "FREEZE");
        assertThat(AlertNarrative.explain(alert(Map.of("kind", "STATELESS"), freeze, 1), freeze))
                .isEqualTo("The issuer (tether) froze 0x1111…1111 in the USDT contract.");
        assertThat(AlertNarrative.explain(alert(Map.of("kind", "SEQUENCE", "steps", List.of("a", "b"), "span", "PT40M"), Map.of(), 1), Map.of()))
                .isEqualTo("The 2 steps of the rule's sequence happened in order for 0x1111…1111 within 40 minutes.");
        assertThat(AlertNarrative.explain(alert(Map.of("kind", "STATELESS"), Map.of(), 1), Map.of())).isNull();
    }
}
