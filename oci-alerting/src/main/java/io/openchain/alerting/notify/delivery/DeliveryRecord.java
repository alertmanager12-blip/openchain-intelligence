package io.openchain.alerting.notify.delivery;

import io.openchain.alerting.notify.Notification;
import java.time.Instant;

/**
 * Outbox row: one notification to one connector. Persisted (PENDING) before any attempt, so a crash never loses a
 * notification; attempts are recorded; exhausted or permanently failing deliveries end in DEAD_LETTER for redrive.
 */
public record DeliveryRecord(String id, String alertId, String connectorId, Notification notification,
                             DeliveryStatus status, int attempts, Instant nextAttemptAt, String lastError,
                             int lastStatusCode, Instant createdAt, Instant updatedAt, Instant deliveredAt) {

    DeliveryRecord attempted(DeliveryStatus s, Instant next, String error, int code, Instant now) {
        return new DeliveryRecord(id, alertId, connectorId, notification, s, attempts + 1, next, error, code, createdAt, now,
                s == DeliveryStatus.DELIVERED ? now : deliveredAt);
    }

    DeliveryRecord deadLetter(String error, Instant now) {
        return new DeliveryRecord(id, alertId, connectorId, notification, DeliveryStatus.DEAD_LETTER, attempts, null, error,
                lastStatusCode, createdAt, now, null);
    }

    DeliveryRecord redriven(Instant now) {
        return new DeliveryRecord(id, alertId, connectorId, notification, DeliveryStatus.PENDING, 0, now, lastError,
                lastStatusCode, createdAt, now, null);
    }
}
