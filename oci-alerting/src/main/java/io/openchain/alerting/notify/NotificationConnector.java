package io.openchain.alerting.notify;

import io.openchain.alerting.rule.ActionType;

/**
 * Notification channel SPI. Implementations are configured by an operator (URLs, secrets, tokens) and referenced
 * by id from rules; rule authors can never supply a destination. Implementations must not throw for delivery
 * failures: they return a {@link DeliveryResult} (unexpected exceptions are treated as retryable by the caller).
 */
public interface NotificationConnector {

    /** Stable id referenced by {@code actions[].connectorId}. */
    String id();

    ActionType type();

    DeliveryResult send(Notification notification);
}
