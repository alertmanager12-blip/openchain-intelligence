package io.openchain.app.chain;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.alerting.alert.AlertRepository;
import io.openchain.alerting.alert.AlertService;
import io.openchain.alerting.alert.InMemoryAlertRepository;
import io.openchain.alerting.engine.EngineConfig;
import io.openchain.alerting.engine.RuleEngine;
import io.openchain.alerting.notify.ConnectorRegistry;
import io.openchain.alerting.notify.delivery.BackoffPolicy;
import io.openchain.alerting.notify.delivery.DeliveryService;
import io.openchain.alerting.notify.delivery.InMemoryDeliveryStore;
import io.openchain.alerting.pipeline.AlertingPipeline;
import io.openchain.alerting.rule.RuleLoader;
import io.openchain.alerting.rule.RuleParser.Format;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.event.ChainEvent.EventType;
import io.openchain.core.model.Network;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Code review I5 (2026-11-13): the rule engine runs inside the transaction that stores alerts. When that transaction
 * fails, the engine state must be undone and the event evaluated again later, or the alert is lost for good.
 */
class EvaluationRetryQueueTest {
    static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");

    final AtomicBoolean databaseDown = new AtomicBoolean();
    final InMemoryAlertRepository stored = new InMemoryAlertRepository();
    /** The alert table: every save fails while the "database" is down (the transaction is then rolled back). */
    final AlertRepository repository = (AlertRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{AlertRepository.class}, (proxy, m, args) -> {
                if (m.getName().equals("save") && databaseDown.get()) throw new IllegalStateException("connection refused");
                try { return m.invoke(stored, args); } catch (InvocationTargetException e) { throw e.getCause(); }
            });
    final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    final RuleEngine engine = new RuleEngine(EngineConfig.defaults());
    final AlertService alerts = new AlertService(repository, clock);
    final AlertingPipeline pipeline = new AlertingPipeline(engine, alerts,
            new DeliveryService(new InMemoryDeliveryStore(), new ConnectorRegistry(), BackoffPolicy.defaults(), clock));
    final EvaluationRetryQueue queue = new EvaluationRetryQueue(e -> pipeline.onEvent(e).engine().disposition(), engine::undoLast, 3);

    EvaluationRetryQueueTest() {
        engine.register(new RuleLoader().load("""
                apiVersion: openchain.io/rule/v1
                id: big
                version: 1.0.0
                name: big transfer
                author: tests
                severity: HIGH
                conditions: { logic: AND, rules: [ { field: transaction.amount, operator: GREATER_THAN, value: 1000 } ] }
                """, Format.YAML));
    }

    static ChainEvent transfer(String id, long sec, String amount) {
        return new ChainEvent(id, EventType.TOKEN_TRANSFER, Network.TRON, 1L, "0xb", T0.plusSeconds(sec), T0.plusSeconds(sec), false,
                Map.of("transaction.from", "TA", "transaction.to", "TB", "transaction.amount", new BigDecimal(amount)));
    }

    @Test void anAlertWhoseTransactionFailedIsCreatedOnRetry() {
        databaseDown.set(true);
        queue.evaluate(transfer("e1", 0, "5000"));
        assertThat(stored.findAll()).isEmpty();
        assertThat(queue.pending()).isEqualTo(1);
        assertThat(queue.failed()).isEqualTo(1);
        assertThat(engine.metrics().undone()).isEqualTo(1);

        queue.evaluate(transfer("e2", 10, "7000")); // still down: queued behind e1, order kept
        assertThat(queue.pending()).isEqualTo(2);

        databaseDown.set(false);
        assertThat(queue.retryPending()).isEqualTo(2);
        assertThat(stored.findAll()).hasSize(1).first().satisfies(a -> assertThat(a.matchedEventIds()).contains("e1", "e2"));
        assertThat(queue.retried()).isEqualTo(2);
        assertThat(queue.pending()).isZero();
    }

    @Test void withoutUndoTheRetryWouldBeTakenForADuplicate() {
        EvaluationRetryQueue noUndo = new EvaluationRetryQueue(e -> pipeline.onEvent(e).engine().disposition(), id -> false, 3);
        databaseDown.set(true);
        noUndo.evaluate(transfer("e1", 0, "5000"));
        databaseDown.set(false);
        noUndo.retryPending();
        assertThat(stored.findAll()).isEmpty(); // the defect this fix removes: the alert is lost
    }

    @Test void aFullQueueDropsAndCounts() {
        databaseDown.set(true);
        for (int i = 0; i < 5; i++) queue.evaluate(transfer("e" + i, i, "5000"));
        assertThat(queue.pending()).isEqualTo(3);
        assertThat(queue.droppedFull()).isEqualTo(2);
    }

    @Test void aRetryOlderThanTheAllowedLatenessIsDroppedAndCounted() {
        databaseDown.set(true);
        queue.evaluate(transfer("old", 0, "5000"));
        databaseDown.set(false);
        // another source of events advanced the engine clock far beyond the lateness (2 minutes) meanwhile
        pipeline.onEvent(transfer("later", Duration.ofMinutes(30).toSeconds(), "1"));
        queue.retryPending();
        assertThat(queue.droppedLate()).isEqualTo(1);
        assertThat(queue.pending()).isZero();
        assertThat(stored.findAll()).isEmpty();
    }
}
