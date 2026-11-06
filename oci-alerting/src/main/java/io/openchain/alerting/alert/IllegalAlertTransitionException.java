package io.openchain.alerting.alert;

public class IllegalAlertTransitionException extends RuntimeException {
    private final AlertStatus from;
    private final AlertStatus to;

    public IllegalAlertTransitionException(String alertId, AlertStatus from, AlertStatus to) {
        super("alert " + alertId + ": transition " + from + " -> " + to + " is not allowed (allowed: "
                + from.allowedTransitions() + ")");
        this.from = from;
        this.to = to;
    }

    public AlertStatus from() { return from; }
    public AlertStatus to() { return to; }
}
