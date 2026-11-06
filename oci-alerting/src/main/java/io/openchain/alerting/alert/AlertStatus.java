package io.openchain.alerting.alert;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Alert lifecycle. Allowed transitions are explicit; anything else throws {@link IllegalAlertTransitionException}.
 * <pre>
 * DETECTED -> PENDING -> NOTIFIED -> ACKNOWLEDGED -> INVESTIGATING -> RESOLVED | FALSE_POSITIVE
 * </pre>
 * Shortcuts: an analyst may acknowledge a PENDING alert (delivery failed) and close (RESOLVED / FALSE_POSITIVE) any
 * open alert. RESOLVED and FALSE_POSITIVE are terminal: a new trigger opens a new alert.
 */
public enum AlertStatus {
    DETECTED, PENDING, NOTIFIED, ACKNOWLEDGED, INVESTIGATING, RESOLVED, FALSE_POSITIVE;

    private static final Map<AlertStatus, Set<AlertStatus>> ALLOWED = Map.of(
            DETECTED, EnumSet.of(PENDING, RESOLVED, FALSE_POSITIVE),
            PENDING, EnumSet.of(NOTIFIED, ACKNOWLEDGED, RESOLVED, FALSE_POSITIVE),
            NOTIFIED, EnumSet.of(ACKNOWLEDGED, RESOLVED, FALSE_POSITIVE),
            ACKNOWLEDGED, EnumSet.of(INVESTIGATING, RESOLVED, FALSE_POSITIVE),
            INVESTIGATING, EnumSet.of(RESOLVED, FALSE_POSITIVE),
            RESOLVED, EnumSet.noneOf(AlertStatus.class),
            FALSE_POSITIVE, EnumSet.noneOf(AlertStatus.class));

    public boolean canTransitionTo(AlertStatus to) { return ALLOWED.get(this).contains(to); }

    public Set<AlertStatus> allowedTransitions() { return EnumSet.copyOf(ALLOWED.get(this).isEmpty() ? EnumSet.noneOf(AlertStatus.class) : ALLOWED.get(this)); }

    public boolean terminal() { return this == RESOLVED || this == FALSE_POSITIVE; }

    /** Not yet seen by a human: subject to escalation. */
    public boolean unacknowledged() { return this == DETECTED || this == PENDING || this == NOTIFIED; }
}
