package io.openchain.app.persistence;

/** Invalid persistence / secrets configuration detected at startup; the message never contains secret values. */
public final class PersistenceStartupException extends RuntimeException {

    private final String action;

    public PersistenceStartupException(String message, String action) {
        super(message);
        this.action = action;
    }

    public PersistenceStartupException(String message, String action, Throwable cause) {
        super(message, cause);
        this.action = action;
    }

    public String action() { return action; }
}
