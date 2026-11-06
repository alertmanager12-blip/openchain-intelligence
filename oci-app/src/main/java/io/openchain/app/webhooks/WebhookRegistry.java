package io.openchain.app.webhooks;

import io.openchain.alerting.alert.Alert;
import io.openchain.alerting.alert.AlertStatus;
import io.openchain.alerting.alert.Priority;
import io.openchain.alerting.notify.ConnectorRegistry;
import io.openchain.alerting.notify.DeliveryResult;
import io.openchain.alerting.notify.Notification;
import io.openchain.alerting.notify.NotificationConnector;
import io.openchain.alerting.notify.connector.WebhookConnector;
import io.openchain.alerting.notify.http.OutboundHttp;
import io.openchain.alerting.rule.ActionType;
import io.openchain.app.security.ApiKeyService;
import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal.EvidenceLevel;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Operator-managed webhook connectors. The signing secret (32 bytes from SecureRandom) is generated here, returned
 * once, and only kept inside the connector instance. Outbound calls go through the {@link OutboundHttp} bean
 * (SafeHttpClient + SsrfGuard in production). {@link ConnectorRegistry} has no removal, so each webhook is
 * registered through a switchable wrapper; a deleted webhook fails every delivery permanently.
 * TODO(persistence): encrypted secret storage (OCI_CRYPTO_MASTER_KEY), secret rotation, Slack/Discord/Telegram/email.
 */
@Service
public class WebhookRegistry {

    public static final List<String> EVENTS = List.of("alert.opened", "alert.updated", "alert.retracted", "alert.status_changed", "webhook.test");

    public record Webhook(String id, String type, String name, String url, List<String> events, boolean enabled, Instant createdAt,
                          Instant lastDeliveryAt, String lastDeliveryStatus) {
        public Webhook { events = List.copyOf(events); }
        Webhook withDelivery(Instant at, boolean ok) { return new Webhook(id, type, name, url, events, enabled, createdAt, at, ok ? "OK" : "FAILED"); }
    }

    public record Created(Webhook webhook, String secret) {}

    public record TestResult(boolean delivered, Integer statusCode, String message) {}

    /** Wrapper registered in the ConnectorRegistry; can be switched off when the webhook is deleted. */
    private static final class Switchable implements NotificationConnector {
        private final WebhookConnector delegate;
        private volatile boolean deleted;
        Switchable(WebhookConnector delegate) { this.delegate = delegate; }
        @Override public String id() { return delegate.id(); }
        @Override public ActionType type() { return ActionType.WEBHOOK; }
        @Override public DeliveryResult send(Notification n) {
            return deleted ? DeliveryResult.permanent(0, "connector deleted") : delegate.send(n);
        }
    }

    private final ConnectorRegistry connectors;
    private final OutboundHttp http;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Webhook> webhooks = new ConcurrentHashMap<>();
    private final Map<String, Switchable> live = new ConcurrentHashMap<>();

    public WebhookRegistry(ConnectorRegistry connectors, OutboundHttp http, Clock clock) {
        this.connectors = connectors;
        this.http = http;
        this.clock = clock;
    }

    /** @param url already validated (https) by the caller; the SSRF guard re-checks at send time */
    public Created create(String name, URI url, List<String> events, String actor) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String secret = "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        // a lowercase slug, so rules can reference it as actions[].connectorId (RuleValidator SLUG)
        String id = "wh-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        // Receivers verify with the secret string as HMAC key (see docs/api "Webhook payload and signature").
        Switchable c = new Switchable(new WebhookConnector(id, url, secret.getBytes(StandardCharsets.UTF_8), http, clock));
        connectors.register(c);
        live.put(id, c);
        Webhook w = new Webhook(id, "WEBHOOK", name, url.toString(), events.isEmpty() ? List.of("alert.opened") : events, true,
                clock.instant(), null, null);
        webhooks.put(id, w);
        ApiKeyService.AUDIT.info("webhook.create id={} name={} host={} actor={}", id, name, url.getHost(), actor);
        return new Created(w, secret);
    }

    public List<Webhook> list() { return webhooks.values().stream().sorted(Comparator.comparing(Webhook::createdAt)).toList(); }

    public Optional<Webhook> find(String id) { return Optional.ofNullable(webhooks.get(id)); }

    public boolean delete(String id, String actor) {
        Webhook w = webhooks.remove(id);
        if (w == null) return false;
        Switchable c = live.remove(id);
        if (c != null) c.deleted = true;
        ApiKeyService.AUDIT.info("webhook.delete id={} actor={}", id, actor);
        return true;
    }

    /** Sends a signed synthetic notification through the real connector (SSRF guard included). */
    public TestResult test(String id, String actor) {
        Webhook w = webhooks.get(id);
        Switchable c = live.get(id);
        if (w == null || c == null) throw new IllegalArgumentException("unknown webhook " + id);
        Instant now = clock.instant();
        Alert probe = Alert.builder().id("webhook-test-" + UUID.randomUUID()).ruleId("webhook.test").ruleVersion("0")
                .title("OpenChain Intelligence webhook test").severity(Severity.INFO).priority(Priority.P4)
                .status(AlertStatus.DETECTED).evidenceLevel(EvidenceLevel.ANOMALY).groupKey("webhook.test").dedupKey("webhook.test")
                .evidence(Map.of("test", true)).firstSeen(now).lastSeen(now).createdAt(now).updatedAt(now).build();
        Notification n = Notification.of(UUID.randomUUID().toString(), ActionType.WEBHOOK, id, Map.of(), probe, now);
        DeliveryResult r;
        try {
            r = c.send(n);
        } catch (RuntimeException e) {
            r = DeliveryResult.retry(0, "connector error: " + e.getClass().getSimpleName());
        }
        boolean ok = r.success();
        webhooks.computeIfPresent(id, (k, v) -> v.withDelivery(now, ok));
        ApiKeyService.AUDIT.info("webhook.test id={} delivered={} status={} actor={}", id, ok, r.statusCode(), actor);
        return new TestResult(ok, r.statusCode() == 0 ? null : r.statusCode(),
                ok ? "test notification delivered" : "delivery failed: " + r.detail());
    }
}
