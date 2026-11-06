package io.openchain.alerting.notify;

import io.openchain.alerting.alert.Alert;
import io.openchain.alerting.alert.Priority;
import io.openchain.alerting.rule.ActionType;
import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.model.Network;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of what is delivered for one (alert, action). Built once when the delivery is enqueued
 * (outbox), so retries send exactly the same content. {@code deliveryId} is the idempotency key.
 */
public record Notification(String deliveryId, ActionType actionType, String connectorId, String alertId, String ruleId,
                           String ruleVersion, String title, Severity severity, Priority priority,
                           EvidenceLevel evidenceLevel, Network network, String groupKey, String dedupKey,
                           long occurrenceCount, Instant firstSeen, Instant lastSeen, List<String> matchedEventIds,
                           Map<String, Object> evidence, Map<String, String> params, List<String> originChain, int hop,
                           Instant createdAt) {

    public Notification {
        matchedEventIds = List.copyOf(matchedEventIds);
        evidence = Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
        params = Map.copyOf(params);
        originChain = List.copyOf(originChain);
    }

    public static Notification of(String deliveryId, ActionType type, String connectorId, Map<String, String> params,
                                  Alert a, Instant now) {
        return new Notification(deliveryId, type, connectorId, a.id(), a.ruleId(), a.ruleVersion(), a.title(),
                a.severity(), a.priority(), a.evidenceLevel(), a.network(), a.groupKey(), a.dedupKey(),
                a.occurrenceCount(), a.firstSeen(), a.lastSeen(), a.matchedEventIds(), a.evidence(), params,
                a.originChain(), a.hop(), now);
    }
}
