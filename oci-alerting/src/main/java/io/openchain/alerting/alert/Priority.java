package io.openchain.alerting.alert;

import io.openchain.core.detection.Severity;

/** Operational urgency (P1 highest). Starts from severity and can be raised by escalation. */
public enum Priority {
    P1, P2, P3, P4;

    public static Priority fromSeverity(Severity s) {
        return switch (s) {
            case CRITICAL -> P1;
            case HIGH -> P2;
            case MEDIUM -> P3;
            case LOW, INFO -> P4;
        };
    }

    /** One step more urgent; P1 stays P1. */
    public Priority raise() { return this == P1 ? P1 : values()[ordinal() - 1]; }
}
