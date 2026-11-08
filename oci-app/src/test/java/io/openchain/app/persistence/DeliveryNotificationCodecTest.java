package io.openchain.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.alerting.alert.AlertSubject;
import io.openchain.alerting.alert.Priority;
import io.openchain.alerting.notify.Notification;
import io.openchain.alerting.rule.ActionType;
import io.openchain.core.detection.Severity;
import io.openchain.core.model.Network;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Queued deliveries keep the alert subject (address, tx, amount) the Telegram message is written from. */
class DeliveryNotificationCodecTest {
    static final Instant T0 = Instant.parse("2026-11-07T20:00:00Z");

    static Notification n(AlertSubject s) {
        return new Notification("d-1", ActionType.TELEGRAM, "tg", "al-1", "wallet-draining", "1.2.0", "Wallet drained",
                Severity.HIGH, Priority.P2, null, Network.ETHEREUM, "g", "k", 1, T0, T0, List.of("e1"),
                Map.of("kind", "AGGREGATION"), Map.of(), List.of(), 0, T0, s);
    }

    @Test void subjectRoundTripsThroughTheStoredPayload() {
        AlertSubject s = new AlertSubject("e1", "NATIVE_TRANSFER", "0xabc", "0xdef", "0x123", 42L, T0,
                Map.of("transaction.amount", new BigDecimal("11.40"), "transaction.token.symbol", "ETH"));
        Notification back = JdbcDeliveryStore.decode(JdbcDeliveryStore.encode(n(s)));
        assertThat(back.subject()).isEqualTo(s);
        assertThat(back.subject().fields().get("transaction.amount")).isEqualTo(new BigDecimal("11.40"));
    }

    @Test void deliveriesQueuedBeforeTheSubjectWasCarriedStillDecode() {
        String legacy = JdbcDeliveryStore.encode(n(null));
        assertThat(legacy).doesNotContain("subject");
        assertThat(JdbcDeliveryStore.decode(legacy).subject()).isNull();
    }
}
