package io.openchain.alerting.notify.connector;

import io.openchain.alerting.engine.RuleEngine;
import io.openchain.alerting.notify.DeliveryResult;
import io.openchain.alerting.notify.Notification;
import io.openchain.alerting.notify.NotificationConnector;
import io.openchain.alerting.rule.ActionType;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.event.ChainEvent.EventType;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Re-injects an alert into the event pipeline as a {@code DETECTION_SIGNAL} ChainEvent, so other rules can correlate
 * on alerts. Loop protection: the event carries {@code internal.hop} (= alert hop + 1) and
 * {@code internal.origin_chain} (rule ids so far); {@link RuleEngine} never lets a rule trigger on an event in whose
 * origin chain it appears, and ignores events beyond {@code maxInternalHops}. The event id is derived from the alert
 * id, so redelivery is idempotent (engine dedup).
 */
public final class InternalEventConnector implements NotificationConnector {

    private final String id;
    private final Consumer<ChainEvent> sink;
    private final Clock clock;

    public InternalEventConnector(String id, Consumer<ChainEvent> sink, Clock clock) {
        this.id = id;
        this.sink = sink;
        this.clock = clock;
    }

    @Override public String id() { return id; }
    @Override public ActionType type() { return ActionType.INTERNAL_EVENT; }

    @Override public DeliveryResult send(Notification n) {
        if (n.network() == null) return DeliveryResult.permanent(0, "alert has no network");
        List<String> chain = new ArrayList<>(n.originChain());
        if (!chain.contains(n.ruleId())) chain.add(n.ruleId());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("alert.id", n.alertId());
        fields.put("alert.rule_id", n.ruleId());
        fields.put("alert.severity", n.severity().name());
        fields.put("alert.group_key", n.groupKey());
        fields.put(RuleEngine.FIELD_HOP, (long) n.hop() + 1);
        fields.put(RuleEngine.FIELD_ORIGIN_CHAIN, String.join(",", chain));
        ChainEvent e = new ChainEvent("internal:alert:" + n.alertId(), EventType.DETECTION_SIGNAL, n.network(), 0L, null,
                n.lastSeen(), clock.instant(), false, fields);
        try {
            sink.accept(e);
            return DeliveryResult.ok(0);
        } catch (RuntimeException ex) {
            return DeliveryResult.retry(0, "internal sink: " + ex.getMessage());
        }
    }
}
