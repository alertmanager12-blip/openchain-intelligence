package io.openchain.app.demo;

import io.openchain.alerting.notify.DeliveryResult;
import io.openchain.alerting.notify.Notification;
import io.openchain.alerting.notify.NotificationConnector;
import io.openchain.alerting.rule.ActionType;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demo / test sink for the {@code default-webhook} connector referenced by the repository rules: it records the
 * notifications it receives (bounded) and sends nothing anywhere. Registered only in demo mode.
 */
public final class DemoNotificationSink implements NotificationConnector {

    private static final Logger log = LoggerFactory.getLogger(DemoNotificationSink.class);
    public static final String CONNECTOR_ID = "default-webhook";
    private static final int MAX = 2_000;

    public record Received(String deliveryId, String alertId, String ruleId, String title, String severity, Instant at) {}

    private final Clock clock;
    private final Deque<Received> received = new ArrayDeque<>();

    public DemoNotificationSink(Clock clock) { this.clock = clock; }

    @Override public String id() { return CONNECTOR_ID; }
    @Override public ActionType type() { return ActionType.WEBHOOK; }

    @Override public synchronized DeliveryResult send(Notification n) {
        received.addFirst(new Received(n.deliveryId(), n.alertId(), n.ruleId(), n.title(), n.severity().name(), clock.instant()));
        while (received.size() > MAX) received.removeLast();
        log.info("demo notification sink: alert {} (rule {}) received, delivery {} - simulated, nothing sent", n.alertId(), n.ruleId(), n.deliveryId());
        return new DeliveryResult(true, false, 0, "recorded by the demo notification sink (simulated)");
    }

    public synchronized List<Received> received() { return List.copyOf(received); }

    public synchronized List<Received> forAlert(String alertId) {
        return received.stream().filter(r -> r.alertId().equals(alertId)).toList();
    }
}
