package io.openchain.app.webhooks;

import io.openchain.alerting.alert.Alert;
import io.openchain.alerting.alert.AlertStatus;
import io.openchain.alerting.alert.Priority;
import io.openchain.alerting.notify.ConnectorRegistry;
import io.openchain.alerting.notify.DeliveryResult;
import io.openchain.alerting.notify.Notification;
import io.openchain.alerting.notify.NotificationConnector;
import io.openchain.alerting.notify.connector.DiscordConnector;
import io.openchain.alerting.notify.connector.SlackConnector;
import io.openchain.alerting.notify.connector.TelegramConnector;
import io.openchain.alerting.notify.connector.WebhookConnector;
import io.openchain.alerting.notify.http.OutboundHttp;
import io.openchain.alerting.rule.ActionType;
import io.openchain.app.config.OciProperties;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Operator-managed notification connectors: WEBHOOK (signed JSON), SLACK, DISCORD (incoming-webhook URLs) and
 * TELEGRAM (chat id; the bot token is server configuration). The WEBHOOK signing secret (32 bytes from SecureRandom)
 * is generated here and returned once; it is stored through {@link ConnectorRepository} (in memory, or encrypted
 * with AES-256-GCM in PostgreSQL together with the target) so that connectors survive a restart. Outbound calls go through the
 * {@link OutboundHttp} bean (SafeHttpClient + SsrfGuard in production). {@link ConnectorRegistry} has no removal, so
 * each connector is registered through a switchable wrapper; a deleted connector fails every delivery permanently.
 * Targets often embed credentials (Slack/Discord URL paths), so only {@link #masked} forms ever leave this class.
 * TODO: secret rotation (two active secrets, ADR-0009), master key rotation. EMAIL/PUSH need a transport (SMTP, FCM) first.
 */
@Service
public class WebhookRegistry {

    public static final List<String> EVENTS = List.of("alert.opened", "alert.updated", "alert.retracted", "alert.status_changed", "webhook.test");

    public record Webhook(String id, String type, String name, String url, List<String> events, boolean enabled, Instant createdAt,
                          Instant lastDeliveryAt, String lastDeliveryStatus) {
        public Webhook { events = List.copyOf(events); }
        public Webhook withDelivery(Instant at, boolean ok) { return new Webhook(id, type, name, url, events, enabled, createdAt, at, ok ? "OK" : "FAILED"); }
    }

    public record Created(Webhook webhook, String secret) {}

    public record TestResult(boolean delivered, Integer statusCode, String message) {}

    /** Connector types that can be created through the API. */
    public static final List<String> TYPES = List.of("WEBHOOK", "SLACK", "DISCORD", "TELEGRAM");

    /** Wrapper registered in the ConnectorRegistry; can be switched off when the connector is deleted. */
    private static final class Switchable implements NotificationConnector {
        private final NotificationConnector delegate;
        private volatile boolean deleted;
        Switchable(NotificationConnector delegate) { this.delegate = delegate; }
        @Override public String id() { return delegate.id(); }
        @Override public ActionType type() { return delegate.type(); }
        @Override public DeliveryResult send(Notification n) {
            return deleted ? DeliveryResult.permanent(0, "connector deleted") : delegate.send(n);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(WebhookRegistry.class);

    private final ConnectorRegistry connectors;
    private final OutboundHttp http;
    private final Clock clock;
    private final String telegramBotToken;
    private final String publicUrl;
    private final ConnectorRepository repository;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Webhook> webhooks = new ConcurrentHashMap<>();
    private final Map<String, Switchable> live = new ConcurrentHashMap<>();

    public WebhookRegistry(ConnectorRegistry connectors, OutboundHttp http, Clock clock, OciProperties props,
                           ConnectorRepository repository) {
        this.connectors = connectors;
        this.http = http;
        this.clock = clock;
        this.repository = repository;
        String token = props.getNotifications().getTelegram().getBotToken();
        this.telegramBotToken = token == null || token.isBlank() ? null : token.strip();
        this.publicUrl = props.getNotifications().getPublicUrl();
        for (ConnectorRepository.Stored s : repository.findAll()) {
            Webhook w = s.webhook();
            NotificationConnector c;
            try {
                c = connector(w.type(), w.id(), w.url(), s.secret());
            } catch (IllegalArgumentException e) {
                // e.g. TELEGRAM stored but OCI_TELEGRAM_BOT_TOKEN no longer configured: listed, deliveries fail permanently
                log.warn("connector {} ({}) cannot be activated: {}", w.id(), w.type(), e.getMessage());
                c = unavailable(w.id(), w.type(), e.getMessage());
            }
            Switchable sw = new Switchable(c);
            connectors.register(sw);
            live.put(w.id(), sw);
            webhooks.put(w.id(), w);
        }
        if (!webhooks.isEmpty()) log.info("{} persisted connector(s) activated", webhooks.size());
    }

    public boolean telegramConfigured() { return telegramBotToken != null; }

    /**
     * @param type   one of {@link #TYPES}
     * @param target https URL (WEBHOOK, SLACK, DISCORD; already validated by the caller, the SSRF guard re-checks at
     *               send time) or chat id (TELEGRAM)
     * @throws IllegalArgumentException when the connector rejects the target (e.g. a non-Slack host) or TELEGRAM is
     *               not configured
     */
    public Created create(String type, String name, String target, List<String> events, String actor) {
        // a lowercase slug, so rules can reference it as actions[].connectorId (RuleValidator SLUG)
        String id = "wh-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String secret = null;
        if ("WEBHOOK".equals(type)) {
            byte[] raw = new byte[32];
            random.nextBytes(raw);
            secret = "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        }
        NotificationConnector connector = connector(type, id, target, secret);
        Webhook w = new Webhook(id, type, name, target, events.isEmpty() ? List.of("alert.opened") : events, true,
                clock.instant(), null, null);
        repository.insert(w, secret, actor); // persisted before activation: a connector is never live but unsaved
        Switchable c = new Switchable(connector);
        connectors.register(c);
        live.put(id, c);
        webhooks.put(id, w);
        ApiKeyService.AUDIT.info("webhook.create id={} type={} name={} target={} actor={}", id, type, name, masked(w), actor);
        return new Created(w, secret);
    }

    private NotificationConnector connector(String type, String id, String target, String secret) {
        return switch (type) {
            // Receivers verify with the secret string as HMAC key (see docs/api "Webhook payload and signature").
            case "WEBHOOK" -> new WebhookConnector(id, URI.create(target), secret.getBytes(StandardCharsets.UTF_8), http, clock);
            case "SLACK" -> new SlackConnector(id, target, http);
            case "DISCORD" -> new DiscordConnector(id, target, http);
            case "TELEGRAM" -> {
                if (telegramBotToken == null) throw new IllegalArgumentException("TELEGRAM is not configured on this server (OCI_TELEGRAM_BOT_TOKEN)");
                yield new TelegramConnector(id, telegramBotToken, target, http, publicUrl);
            }
            default -> throw new IllegalArgumentException("unsupported connector type " + type);
        };
    }

    private static NotificationConnector unavailable(String id, String type, String reason) {
        ActionType t;
        try {
            t = ActionType.valueOf(type);
        } catch (IllegalArgumentException e) {
            t = ActionType.WEBHOOK;
        }
        ActionType actionType = t;
        return new NotificationConnector() {
            @Override public String id() { return id; }
            @Override public ActionType type() { return actionType; }
            @Override public DeliveryResult send(Notification n) { return DeliveryResult.permanent(0, "connector unavailable: " + reason); }
        };
    }

    /**
     * Display form of a connector target: URLs keep only scheme and host (Slack/Discord/webhook paths and queries
     * carry tokens), chat ids keep their last 4 characters.
     */
    public static String masked(Webhook w) {
        String t = w.url();
        if (!"TELEGRAM".equals(w.type())) {
            try {
                URI u = URI.create(t);
                return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + "/…";
            } catch (IllegalArgumentException e) {
                return "…";
            }
        }
        return t.length() <= 4 ? "…" : "…" + t.substring(t.length() - 4);
    }

    public List<Webhook> list() { return webhooks.values().stream().sorted(Comparator.comparing(Webhook::createdAt)).toList(); }

    public Optional<Webhook> find(String id) { return Optional.ofNullable(webhooks.get(id)); }

    public boolean delete(String id, String actor) {
        if (!webhooks.containsKey(id)) return false;
        repository.delete(id);
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
        Notification n = Notification.of(UUID.randomUUID().toString(), c.type(), id, Map.of(), probe, now);
        DeliveryResult r;
        try {
            r = c.send(n);
        } catch (RuntimeException e) {
            r = DeliveryResult.retry(0, "connector error: " + e.getClass().getSimpleName());
        }
        boolean ok = r.success();
        webhooks.computeIfPresent(id, (k, v) -> v.withDelivery(now, ok));
        repository.updateDelivery(id, now, ok ? "OK" : "FAILED");
        ApiKeyService.AUDIT.info("webhook.test id={} delivered={} status={} actor={}", id, ok, r.statusCode(), actor);
        return new TestResult(ok, r.statusCode() == 0 ? null : r.statusCode(),
                ok ? "test notification delivered" : "delivery failed: " + r.detail());
    }
}
