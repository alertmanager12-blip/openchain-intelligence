package io.openchain.core.provider;

/** Provider failure. {@code retryable} distinguishes transient errors (timeouts, 429, 5xx) from permanent ones. */
public class ProviderException extends RuntimeException {
    private final boolean retryable;
    public ProviderException(String message, boolean retryable, Throwable cause) { super(message, cause); this.retryable = retryable; }
    public ProviderException(String message, boolean retryable) { this(message, retryable, null); }
    public boolean retryable() { return retryable; }
}
