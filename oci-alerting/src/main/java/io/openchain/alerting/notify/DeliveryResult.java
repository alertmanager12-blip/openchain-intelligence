package io.openchain.alerting.notify;

/** Outcome of one delivery attempt. {@code statusCode} is 0 when not HTTP. */
public record DeliveryResult(boolean success, boolean retryable, int statusCode, String detail) {

    public static DeliveryResult ok(int status) { return new DeliveryResult(true, false, status, "delivered"); }

    public static DeliveryResult retry(int status, String detail) { return new DeliveryResult(false, true, status, detail); }

    public static DeliveryResult permanent(int status, String detail) { return new DeliveryResult(false, false, status, detail); }
}
