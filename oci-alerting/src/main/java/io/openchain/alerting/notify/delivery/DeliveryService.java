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

    public DeliveryRecord enqueue(Alert alert, ActionSpec action) {
        Instant now = clock.instant();
        String id = ids.get();
        Notification n = Notification.of(id, action.type(), action.connectorId(), action.params(), alert, now);
        return store.save(new DeliveryRecord(id, alert.id(), action.connectorId(), n, DeliveryStatus.PENDING, 0, now,
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
            onDelivered.accept(done);
            return done;
        }
        int attempts = r.attempts() + 1;
        if (!res.retryable() || attempts >= backoff.maxAttempts())
            return dead(r.attempted(DeliveryStatus.DEAD_LETTER, null, res.detail(), res.statusCode(), now));
        return store.save(r.attempted(DeliveryStatus.PENDING, now.plus(backoff.delay(attempts)), res.detail(), res.statusCode(), now));
    }

    private DeliveryRecord dead(DeliveryRecord r) {
        DeliveryRecord saved = store.save(r);
        onDeadLetter.accept(saved);
        return saved;
    }
}
