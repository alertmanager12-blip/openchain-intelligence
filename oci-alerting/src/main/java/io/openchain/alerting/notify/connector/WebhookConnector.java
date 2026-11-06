package io.openchain.alerting.notify.connector;

import io.openchain.alerting.notify.DeliveryResult;
import io.openchain.alerting.notify.Notification;
import io.openchain.alerting.notify.NotificationConnector;
import io.openchain.alerting.notify.NotificationFormatter;
import io.openchain.alerting.notify.WebhookSignature;
import io.openchain.alerting.notify.http.OutboundHttp;
import io.openchain.alerting.rule.ActionType;
import java.net.URI;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generic signed webhook. Body: {@link NotificationFormatter#json}. Headers: {@code X-OCI-Signature}
 * (HMAC-SHA256 over "timestamp.body"), {@code X-OCI-Delivery-Id} (stable across retries) and
 * {@code X-OCI-Event: alert.opened}. The URL is configured by the operator and SSRF-checked on every attempt.
 */
public final class WebhookConnector implements NotificationConnector {

    private final String id;
    private final URI url;
    private final byte[] secret;
    private final OutboundHttp http;
    private final Clock clock;

    public WebhookConnector(String id, URI url, byte[] secret, OutboundHttp http, Clock clock) {
        if (secret == null || secret.length < WebhookSignature.MIN_SECRET_BYTES)
            throw new IllegalArgumentException("webhook secret must be at least " + WebhookSignature.MIN_SECRET_BYTES + " bytes");
        this.id = id;
        this.url = url;
        this.secret = secret.clone();
        this.http = http;
        this.clock = clock;
    }

    @Override public String id() { return id; }
    @Override public ActionType type() { return ActionType.WEBHOOK; }

    @Override public DeliveryResult send(Notification n) {
        byte[] body = NotificationFormatter.json(n);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put(WebhookSignature.HEADER, WebhookSignature.header(secret, clock.instant().getEpochSecond(), body));
        headers.put(WebhookSignature.IDEMPOTENCY_HEADER, n.deliveryId());
        headers.put(WebhookSignature.EVENT_HEADER, WebhookSignature.EVENT_ALERT_OPENED);
        return HttpConnectorSupport.post(http, url, headers, body);
    }
}
