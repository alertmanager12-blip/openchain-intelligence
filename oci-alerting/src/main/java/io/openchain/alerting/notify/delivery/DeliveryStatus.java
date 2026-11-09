package io.openchain.alerting.notify.delivery;

/**
 * PENDING → DELIVERED or DEAD_LETTER. SKIPPED: no connector could receive it when the alert was raised (none configured),
 * so no attempt is made; the reason is kept in {@code lastError}.
 */
public enum DeliveryStatus { PENDING, DELIVERED, DEAD_LETTER, SKIPPED }
