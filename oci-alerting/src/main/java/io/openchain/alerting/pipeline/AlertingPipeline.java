package io.openchain.alerting.pipeline;

import io.openchain.alerting.alert.Alert;
import io.openchain.alerting.alert.AlertService;
import io.openchain.alerting.alert.AlertStatus;
import io.openchain.alerting.engine.EngineResult;
import io.openchain.alerting.engine.RuleEngine;
import io.openchain.alerting.engine.RuleMatch;
import io.openchain.alerting.notify.delivery.DeliveryRecord;
import io.openchain.alerting.notify.delivery.DeliveryService;
import io.openchain.alerting.rule.Rule;
import io.openchain.core.event.ChainEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Wires engine -> alerts -> outbox. New alerts with actions move DETECTED -> PENDING and get one delivery per
 * action; the first successful delivery moves the alert to NOTIFIED. Grouped occurrences do not notify again.
 */
public final class AlertingPipeline {

    public record Outcome(EngineResult engine, List<Alert> created, List<Alert> grouped, List<DeliveryRecord> enqueued) {}

    private final RuleEngine engine;
    private final AlertService alerts;
    private final DeliveryService delivery;

    public AlertingPipeline(RuleEngine engine, AlertService alerts, DeliveryService delivery) {
        this.engine = engine;
        this.alerts = alerts;
        this.delivery = delivery;
        delivery.onDelivered(this::markNotified);
        delivery.onDeadLetter(d -> alerts.find(d.alertId()).ifPresent(a -> alerts.comment(a.id(), AlertService.SYSTEM + ":delivery",
                "delivery via " + d.connectorId() + " failed permanently after " + d.attempts() + " attempt(s): " + d.lastError())));
    }

    public Outcome onEvent(ChainEvent event) {
        EngineResult r = engine.process(event);
        if (r.retractedEventId() != null) alerts.onRetraction(r.retractedEventId());
        List<Alert> created = new ArrayList<>(), grouped = new ArrayList<>();
        List<DeliveryRecord> enqueued = new ArrayList<>();
        for (RuleMatch m : r.matches()) {
            Rule rule = engine.rule(m.ruleId()).map(c -> c.rule()).orElse(null);
            if (rule == null) continue; // unregistered concurrently
            AlertService.MatchOutcome o = alerts.onMatch(m, rule.cooldown());
            if (!o.created()) { grouped.add(o.alert()); continue; }
            Alert a = o.alert();
            if (!rule.actions().isEmpty()) {
                a = alerts.transition(a.id(), AlertStatus.PENDING, AlertService.SYSTEM,
                        "dispatching " + rule.actions().size() + " action(s)");
                for (Rule.ActionSpec action : rule.actions()) enqueued.add(delivery.enqueue(a, action));
            }
            created.add(a);
        }
        return new Outcome(r, created, grouped, enqueued);
    }

    /** Runs due deliveries; call periodically. */
    public List<DeliveryRecord> dispatch(int limit) { return delivery.processDue(limit); }

    private void markNotified(DeliveryRecord d) {
        alerts.find(d.alertId()).filter(a -> a.status() == AlertStatus.PENDING).ifPresent(a ->
                alerts.transition(a.id(), AlertStatus.NOTIFIED, AlertService.SYSTEM, "delivered via " + d.connectorId()));
    }
}
