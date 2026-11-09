package io.openchain.alerting.notify.delivery;

import io.openchain.alerting.alert.Alert;
import io.openchain.alerting.notify.ConnectorRegistry;
import io.openchain.alerting.notify.DeliveryResult;
import io.openchain.alerting.notify.Notification;
import io.openchain.alerting.notify.NotificationConnector;
import io.openchain.alerting.rule.Rule.ActionSpec;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Outbox-style delivery with retry, exponential backoff and dead letter. {@link #enqueue} only persists;
 * {@link #processDue} (called by a scheduler) performs attempts. Never throws because of a connector.
 * Attempts are serialized per service instance; a multi-node deployment needs a {@link DeliveryStore} that claims
 * rows atomically (e.g. {@code SELECT ... FOR UPDATE SKIP LOCKED}). Receivers deduplicate on the delivery id.
 */
public final class DeliveryService {

    private final DeliveryStore store;
    private final ConnectorRegistry connectors;
    private final BackoffPolicy backoff;
    private final Clock clock;
    private final Supplier<String> ids;
    private volatile Consumer<DeliveryRecord> onDelivered = r -> { };
    private volatile Consumer<DeliveryRecord> onDeadLetter = r -> { };
    private final java.util.concurrent.atomic.AtomicLong delivered = new java.util.concurrent.atomic.AtomicLong(),
            deadLettered = new java.util.concurrent.atomic.AtomicLong(), skipped = new java.util.concurrent.atomic.AtomicLong();

    /** Deliveries that reached their connector / ended in dead letter / were skipped (no connector), since start. */
    public long deliveredCount() { return delivered.get(); }
    public long deadLetteredCount() { return deadLettered.get(); }
    public long skippedCount() { return skipped.get(); }

    /** Connector that receives the actions addressed to the rule pack's placeholder ids (see {@link #PLACEHOLDERS}). */
    private volatile String defaultConnectorId;

    /**
     * Connector ids used by the shipped rule pack to mean "the operator's default channel". When no connector with such an
     * id exists, the action goes to {@link #setDefaultConnector the configured default connector}, with that connector's
     * type; when none is configured, the delivery is SKIPPED (never dead-lettered after a doomed attempt).
     */
    public static final java.util.Set<String> PLACEHOLDERS = java.util.Set.of("default-webhook", "default");

    public DeliveryService(DeliveryStore store, ConnectorRegistry connectors, BackoffPolicy backoff, Clock clock, Supplier<String> ids) {
        this.store = store;
        this.connectors = connectors;
        this.backoff = backoff;
        this.clock = clock;
        this.ids = ids;
    }

    public DeliveryService(DeliveryStore store, ConnectorRegistry connectors, BackoffPolicy backoff, Clock clock) {
        this(store, connectors, backoff, clock, () -> UUID.randomUUID().toString());
    }

    public void onDelivered(Consumer<DeliveryRecord> listener) { this.onDelivered = listener; }

    public void onDeadLetter(Consumer<DeliveryRecord> listener) { this.onDeadLetter = listener; }

    /** @param connectorId default channel for the rule pack's placeholder ids; null or blank = none */
    public void setDefaultConnector(String connectorId) {
        this.defaultConnectorId = connectorId == null || connectorId.isBlank() ? null : connectorId.strip();
    }

    public String defaultConnector() { return defaultConnectorId; }

    public DeliveryRecord enqueue(Alert alert, ActionSpec action) {
        Instant now = clock.instant();
        String id = ids.get();
        String target = action.connectorId();
        var type = action.type();
        if (PLACEHOLDERS.contains(target) && connectors.find(target).isEmpty() && defaultConnectorId != null) {
            var def = connectors.find(defaultConnectorId);
            if (def.isPresent()) {
                target = def.get().id();
                type = def.get().type(); // the pack says WEBHOOK; the operator's default channel may be Telegram, Slack...
            }
        }
        Notification n = Notification.of(id, type, target, action.params(), alert, now);
        if (connectors.find(target).isEmpty()) {
            String why = PLACEHOLDERS.contains(target)
                    ? "no notification channel configured: set OCI_DEFAULT_CONNECTOR or route the rule to a connector"
                    : "unknown connector '" + target + "'";
            skipped.incrementAndGet();
            return store.save(new DeliveryRecord(id, alert.id(), target, n, DeliveryStatus.SKIPPED, 0, null, why, 0, now, now, null));
        }
        return store.save(new DeliveryRecord(id, alert.id(), target, n, DeliveryStatus.PENDING, 0, now,
                null, 0, now, now, null));
    }

    /** Attempts every due delivery (up to {@code limit}); returns the updated records. */
    public synchronized List<DeliveryRecord> processDue(int limit) {
        List<DeliveryRecord> out = new ArrayList<>();
        for (DeliveryRecord r : store.findDue(clock.instant(), limit)) out.add(attempt(r));
        return out;
    }

    public List<DeliveryRecord> deadLetters() { return store.findByStatus(DeliveryStatus.DEAD_LETTER); }

    /** Moves a dead-lettered delivery back to PENDING (operator action after fixing the connector). */
    public synchronized DeliveryRecord redrive(String deliveryId) {
        DeliveryRecord r = store.find(deliveryId).orElseThrow(() -> new IllegalArgumentException("unknown delivery " + deliveryId));
        if (r.status() != DeliveryStatus.DEAD_LETTER) throw new IllegalStateException("only DEAD_LETTER deliveries can be redriven");
        return store.save(r.redriven(clock.instant()));
    }

    private DeliveryRecord attempt(DeliveryRecord r) {
        Instant now = clock.instant();
        NotificationConnector c = connectors.find(r.connectorId()).orElse(null);
        if (c == null) return dead(r.deadLetter("unknown connector '" + r.connectorId() + "'", now));
        if (c.type() != r.notification().actionType())
            return dead(r.deadLetter("connector '" + c.id() + "' is " + c.type() + ", action is " + r.notification().actionType(), now));
        DeliveryResult res;
        try {
            res = c.send(r.notification());
        } catch (RuntimeException e) {
            res = DeliveryResult.retry(0, "connector error: " + e.getClass().getSimpleName());
        }
        if (res.success()) {
            DeliveryRecord done = store.save(r.attempted(DeliveryStatus.DELIVERED, null, null, res.statusCode(), now));
            delivered.incrementAndGet();
            onDelivered.accept(done);
            return done;
        }
        int attempts = r.attempts() + 1;
        if (!res.retryable() || attempts >= backoff.maxAttempts())
            return dead(r.attempted(DeliveryStatus.DEAD_LETTER, null, res.detail(), res.statusCode(), now));
        return store.save(r.attempted(DeliveryStatus.PENDING, now.plus(backoff.delay(attempts)), res.detail(), res.statusCode(), now));
    }

    private DeliveryRecord dead(DeliveryRecord r) {
        deadLettered.incrementAndGet();
        DeliveryRecord saved = store.save(r);
        onDeadLetter.accept(saved);
        return saved;
    }
}
