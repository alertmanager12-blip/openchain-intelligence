package io.openchain.alerting.alert;

import java.time.Duration;

/**
 * Unacknowledged alerts (DETECTED / PENDING / NOTIFIED) older than {@code ackTimeout} since creation or since the
 * last escalation get their priority raised by one level, up to {@code maxEscalations} times.
 */
public record EscalationPolicy(Duration ackTimeout, int maxEscalations) {
    public EscalationPolicy {
        if (ackTimeout.isNegative() || ackTimeout.isZero()) throw new IllegalArgumentException("ackTimeout must be > 0");
        if (maxEscalations < 0) throw new IllegalArgumentException("maxEscalations < 0");
    }

    public static EscalationPolicy defaults() { return new EscalationPolicy(Duration.ofMinutes(30), 3); }

    public static EscalationPolicy disabled() { return new EscalationPolicy(Duration.ofDays(3650), 0); }
}
