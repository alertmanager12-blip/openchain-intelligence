package io.openchain.alerting.notify;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook signature: header {@code X-OCI-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, "t.body")>}.
 * Receivers must verify with {@link #verify}, which uses a constant-time comparison and rejects stale timestamps
 * (replay protection), and should deduplicate on the {@code X-OCI-Delivery-Id} header.
 */
public final class WebhookSignature {

    public static final String HEADER = "X-OCI-Signature";
    public static final String IDEMPOTENCY_HEADER = "X-OCI-Delivery-Id";
    public static final String EVENT_HEADER = "X-OCI-Event";
    /** Event sent with every alert notification (deliveries are enqueued when an alert is opened). */
    public static final String EVENT_ALERT_OPENED = "alert.opened";
    public static final int MIN_SECRET_BYTES = 32;

    private WebhookSignature() {}

    public static String header(byte[] secret, long timestampSeconds, byte[] body) {
        return "t=" + timestampSeconds + ",v1=" + HexFormat.of().formatHex(hmac(secret, timestampSeconds, body));
    }

    /** @return true iff the header is well-formed, fresh (|now - t| <= tolerance) and the MAC matches */
    public static boolean verify(byte[] secret, String header, byte[] body, Instant now, Duration tolerance) {
        if (header == null || header.length() > 512) return false;
        Long ts = null;
        byte[] provided = null;
        for (String part : header.split(",")) {
            String p = part.trim();
            try {
                if (p.startsWith("t=")) ts = Long.parseLong(p.substring(2));
                else if (p.startsWith("v1=")) provided = HexFormat.of().parseHex(p.substring(3));
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        if (ts == null || provided == null) return false;
        if (Math.abs(now.getEpochSecond() - ts) > tolerance.toSeconds()) return false;
        return MessageDigest.isEqual(hmac(secret, ts, body), provided);
    }

    private static byte[] hmac(byte[] secret, long ts, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((ts + ".").getBytes(StandardCharsets.US_ASCII));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
