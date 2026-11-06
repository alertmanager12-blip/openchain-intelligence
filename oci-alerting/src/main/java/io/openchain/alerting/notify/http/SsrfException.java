package io.openchain.alerting.notify.http;

/** The destination is not allowed by the outbound policy. Never retryable. */
public class SsrfException extends RuntimeException {
    public SsrfException(String message) { super(message); }
}
