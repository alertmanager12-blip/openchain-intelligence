package io.openchain.alerting.alert;

/**
 * Optimistic-locking failure: the alert was changed by someone else (another instance or request) since it was read.
 * The caller should re-read the alert and retry the operation.
 */
public final class ConcurrentAlertModificationException extends RuntimeException {

    private final String alertId;

    public ConcurrentAlertModificationException(String alertId, long expectedVersion) {
        super("alert " + alertId + " was modified concurrently (expected version " + expectedVersion + "); re-read and retry");
        this.alertId = alertId;
    }

    public String alertId() { return alertId; }
}
