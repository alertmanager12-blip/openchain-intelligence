package io.openchain.alerting.notify;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.alerting.alert.Alert;
import io.openchain.alerting.alert.Priority;
import io.openchain.alerting.notify.delivery.BackoffPolicy;
import io.openchain.alerting.notify.delivery.DeliveryRecord;
import io.openchain.alerting.notify.delivery.DeliveryService;
import io.openchain.alerting.notify.delivery.DeliveryStatus;
import io.openchain.alerting.notify.delivery.InMemoryDeliveryStore;
import io.openchain.alerting.rule.ActionType;
import io.openchain.alerting.rule.Rule.ActionSpec;
import io.openchain.core.detection.Severity;
import io.openchain.core.model.Network;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Production finding (2026-11-09): every rule of the pack routes to {@code default-webhook}, a connector that only exists
 * in demo mode, so 5,675 notifications were dead-lettered with "unknown connector". The placeholder now resolves to the
 * operator's default connector, or the delivery is SKIPPED with the reason (never a doomed attempt).
 */
class DefaultConnectorRoutingTest {
    static final Instant T0 = Instant.parse("2026-11-09T10:00:00Z");
    final InMemoryDeliveryStore store = new InMemoryDeliveryStore();
    final ConnectorRegistry connectors = new ConnectorRegistry();
    final DeliveryService delivery = new DeliveryService(store, connectors, BackoffPolicy.defaults(), Clock.fixed(T0, ZoneOffset.UTC));
    final List<Notification> sent = new CopyOnWriteArrayList<>();

    NotificationConnector connector(String id, ActionType type) {
        return new NotificationConnector() {
            @Override public String id() { return id; }
            @Override public ActionType type() { return type; }
            @Override public DeliveryResult send(Notification n) { sent.add(n); return DeliveryResult.ok(200); }
        };
    }

    static Alert alert() {
        return Alert.builder().id("al-1").ruleId("large-usdt-transfer").ruleVersion("1.0.0").title("Large USDT transfer")
                .severity(Severity.HIGH).priority(Priority.P2).network(Network.TRON).groupKey("g").dedupKey("d")
                .firstSeen(T0).lastSeen(T0).occurrenceCount(1).addEventIds(List.of("e1")).createdAt(T0).updatedAt(T0)
                .evidence(Map.of()).build();
    }

    static ActionSpec packAction() { return new ActionSpec(ActionType.WEBHOOK, "default-webhook", Map.of()); }

    @Test void placeholderGoesToTheDefaultConnectorWithItsOwnType() {
        connectors.register(connector("wh-telegram", ActionType.TELEGRAM));
        delivery.setDefaultConnector("wh-telegram");
        DeliveryRecord r = delivery.enqueue(alert(), packAction());
        assertThat(r.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(r.connectorId()).isEqualTo("wh-telegram");
        assertThat(delivery.processDue(10)).singleElement().satisfies(d -> assertThat(d.status()).isEqualTo(DeliveryStatus.DELIVERED));
        assertThat(sent).singleElement().satisfies(n -> assertThat(n.actionType()).isEqualTo(ActionType.TELEGRAM));
    }

    @Test void withoutDefaultConnectorTheDeliveryIsSkippedWithItsReasonAndNeverAttempted() {
        DeliveryRecord r = delivery.enqueue(alert(), packAction());
        assertThat(r.status()).isEqualTo(DeliveryStatus.SKIPPED);
        assertThat(r.lastError()).contains("OCI_DEFAULT_CONNECTOR");
        assertThat(r.attempts()).isZero();
        assertThat(delivery.processDue(10)).isEmpty();
        assertThat(delivery.deadLetters()).isEmpty();
    }

    @Test void aDefaultConnectorThatDoesNotExistAlsoSkips() {
        delivery.setDefaultConnector("wh-deleted");
        assertThat(delivery.enqueue(alert(), packAction()).status()).isEqualTo(DeliveryStatus.SKIPPED);
    }

    @Test void anUnknownExplicitConnectorIsSkippedNotDeadLettered() {
        DeliveryRecord r = delivery.enqueue(alert(), new ActionSpec(ActionType.SLACK, "wh-removed", Map.of()));
        assertThat(r.status()).isEqualTo(DeliveryStatus.SKIPPED);
        assertThat(r.lastError()).isEqualTo("unknown connector 'wh-removed'");
    }

    @Test void aRealConnectorNamedLikeThePlaceholderIsUsedAsIs() {
        // demo mode registers a real "default-webhook" sink: no redirection to the default connector
        connectors.register(connector("default-webhook", ActionType.WEBHOOK));
        connectors.register(connector("wh-telegram", ActionType.TELEGRAM));
        delivery.setDefaultConnector("wh-telegram");
        assertThat(delivery.enqueue(alert(), packAction()).connectorId()).isEqualTo("default-webhook");
    }
}
