package io.openchain.alerting.notify.connector;

import io.openchain.alerting.notify.DeliveryResult;
import io.openchain.alerting.notify.Notification;
import io.openchain.alerting.notify.NotificationConnector;
import io.openchain.alerting.rule.ActionType;
import java.util.List;
import java.util.Map;

/** Mobile/web push. Transport (FCM, APNs, WebPush) is delegated to a {@link PushSender}. */
public final class PushConnector implements NotificationConnector {

    public interface PushSender {
        void send(PushMessage message) throws Exception;
    }

    public record PushMessage(String topic, String title, String body, Map<String, String> data, String idempotencyId) {
        public PushMessage { data = Map.copyOf(data); }
    }

    /** Stub sender recording messages in memory. */
    public static final class InMemoryPushSender implements PushSender {
        private final List<PushMessage> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override public void send(PushMessage m) { sent.add(m); }
        public List<PushMessage> sent() { return List.copyOf(sent); }
    }

    private final String id;
    private final PushSender sender;
    private final String topic;

    public PushConnector(String id, PushSender sender, String topic) {
        this.id = id;
        this.sender = sender;
        this.topic = topic;
    }

    @Override public String id() { return id; }
    @Override public ActionType type() { return ActionType.PUSH; }

    @Override public DeliveryResult send(Notification n) {
        String title = "[" + n.severity() + "] " + n.title();
        String body = n.network() + " " + n.groupKey() + " x" + n.occurrenceCount();
        try {
            sender.send(new PushMessage(topic, title.length() > 120 ? title.substring(0, 120) : title,
                    body.length() > 240 ? body.substring(0, 240) : body,
                    Map.of("alertId", n.alertId(), "ruleId", n.ruleId()), n.deliveryId()));
            return DeliveryResult.ok(0);
        } catch (Exception e) {
            return DeliveryResult.retry(0, "push transport: " + e.getMessage());
        }
    }
}
