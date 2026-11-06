package io.openchain.alerting.notify;

import static io.openchain.alerting.Fx.T0;
import static io.openchain.alerting.Fx.header;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.openchain.alerting.Fx;
import io.openchain.alerting.alert.Alert;
import io.openchain.alerting.alert.AlertService;
import io.openchain.alerting.alert.AlertStatus;
import io.openchain.alerting.alert.EscalationPolicy;
import io.openchain.alerting.alert.InMemoryAlertRepository;
import io.openchain.alerting.alert.Priority;
import io.openchain.alerting.engine.EngineConfig;
import io.openchain.alerting.engine.RuleEngine;
import io.openchain.alerting.notify.connector.InternalEventConnector;
import io.openchain.alerting.notify.connector.WebhookConnector;
import io.openchain.alerting.notify.delivery.BackoffPolicy;
import io.openchain.alerting.notify.delivery.DeliveryRecord;
import io.openchain.alerting.notify.delivery.DeliveryService;
import io.openchain.alerting.notify.delivery.DeliveryStatus;
import io.openchain.alerting.notify.delivery.InMemoryDeliveryStore;
import io.openchain.alerting.pipeline.AlertingPipeline;
import io.openchain.alerting.rule.ActionType;
import io.openchain.alerting.rule.Rule.ActionSpec;
import io.openchain.core.detection.Severity;
import io.openchain.core.event.ChainEvent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeliveryAndPipelineTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-06-01T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    static final DeliveryResult THROW = DeliveryResult.permanent(-1, "throw");

    /** Connector returning scripted results. */
    static final class ScriptedConnector implements NotificationConnector {
        final Deque<DeliveryResult> script = new ArrayDeque<>();
        final List<Notification> received = new ArrayList<>();
        final String id; final ActionType type;
        ScriptedConnector(String id, ActionType type) { this.id = id; this.type = type; }
        @Override public String id() { return id; }
        @Override public ActionType type() { return type; }
        @Override public DeliveryResult send(Notification n) {
            received.add(n);
            DeliveryResult r = script.isEmpty() ? DeliveryResult.ok(200) : script.poll();
            if (r == THROW) throw new IllegalStateException("boom");
            return r;
        }
    }

    MutableClock clock;
    ConnectorRegistry registry;
    InMemoryDeliveryStore store;
    DeliveryService delivery;
    AlertService alerts;
    ScriptedConnector hook;

    /** Deterministic backoff: 10s, 20s, 40s ... capped at 60s, no jitter, 4 attempts. */
    static final BackoffPolicy BACKOFF = new BackoffPolicy(Duration.ofSeconds(10), 2, Duration.ofSeconds(60), 4, 0, RandomGenerator.getDefault());

    @BeforeEach void setUp() {
        clock = new MutableClock();
        registry = new ConnectorRegistry();
        hook = new ScriptedConnector("hook", ActionType.WEBHOOK);
        registry.register(hook);
        store = new InMemoryDeliveryStore();
        AtomicInteger ids = new AtomicInteger();
        delivery = new DeliveryService(store, registry, BACKOFF, clock, () -> "d" + ids.incrementAndGet());
        AtomicInteger aids = new AtomicInteger();
        alerts = new AlertService(new InMemoryAlertRepository(), clock, EscalationPolicy.defaults(), () -> "a" + aids.incrementAndGet());
    }

    Alert alert() {
        return Alert.builder().id("a-x").ruleId("r").ruleVersion("1.0.0").title("t").severity(Severity.LOW).priority(Priority.P4)
                .firstSeen(T0).lastSeen(T0).createdAt(clock.now).updatedAt(clock.now).build();
    }

    @Test void backoffIsExponentialAndCapped() {
        assertThat(BACKOFF.delay(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(BACKOFF.delay(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(BACKOFF.delay(3)).isEqualTo(Duration.ofSeconds(40));
        assertThat(BACKOFF.delay(4)).isEqualTo(Duration.ofSeconds(60));
        assertThat(BACKOFF.delay(50)).isEqualTo(Duration.ofSeconds(60));
        BackoffPolicy jittered = new BackoffPolicy(Duration.ofSeconds(10), 2, Duration.ofMinutes(1), 3, 0.5, RandomGenerator.getDefault());
        for (int i = 0; i < 100; i++) assertThat(jittered.delay(1)).isBetween(Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    @Test void retriesWithBackoffThenSucceeds() {
        hook.script.add(DeliveryResult.retry(503, "HTTP 503"));
        hook.script.add(DeliveryResult.retry(0, "timeout"));
        DeliveryRecord r = delivery.enqueue(alert(), new ActionSpec(ActionType.WEBHOOK, "hook", Map.of()));
        assertThat(r.status()).isEqualTo(DeliveryStatus.PENDING);

        assertThat(delivery.processDue(10)).singleElement().satisfies(x -> {
            assertThat(x.attempts()).isEqualTo(1);
            assertThat(x.nextAttemptAt()).isEqualTo(clock.now.plusSeconds(10));
            assertThat(x.lastError()).isEqualTo("HTTP 503");
        });
        assertThat(delivery.processDue(10)).isEmpty(); // not due yet
        clock.now = clock.now.plusSeconds(10);
        assertThat(delivery.processDue(10).getFirst().nextAttemptAt()).isEqualTo(clock.now.plusSeconds(20));
        clock.now = clock.now.plusSeconds(20);
        DeliveryRecord done = delivery.processDue(10).getFirst();
        assertThat(done.status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(done.attempts()).isEqualTo(3);
        assertThat(hook.received).hasSize(3).allSatisfy(n -> assertThat(n.deliveryId()).isEqualTo("d1")); // same idempotency id
    }

    @Test void exhaustedRetriesGoToDeadLetterAndCanBeRedriven() {
        for (int i = 0; i < 4; i++) hook.script.add(DeliveryResult.retry(500, "HTTP 500"));
        List<DeliveryRecord> dead = new ArrayList<>();
        delivery.onDeadLetter(dead::add);
        DeliveryRecord r = delivery.enqueue(alert(), new ActionSpec(ActionType.WEBHOOK, "hook", Map.of()));
        for (int i = 0; i < 4; i++) { delivery.processDue(10); clock.now = clock.now.plusSeconds(120); }
        assertThat(store.find(r.id()).orElseThrow().status()).isEqualTo(DeliveryStatus.DEAD_LETTER);
        assertThat(store.find(r.id()).orElseThrow().attempts()).isEqualTo(4);
        assertThat(delivery.deadLetters()).hasSize(1);
        assertThat(dead).hasSize(1);
        assertThat(delivery.processDue(10)).isEmpty();

        DeliveryRecord again = delivery.redrive(r.id());
        assertThat(again.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(delivery.processDue(10).getFirst().status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThatThrownBy(() -> delivery.redrive(r.id())).isInstanceOf(IllegalStateException.class);
    }

    @Test void permanentFailuresUnknownConnectorsAndExceptions() {
        hook.script.add(DeliveryResult.permanent(400, "HTTP 400"));
        DeliveryRecord perm = delivery.enqueue(alert(), new ActionSpec(ActionType.WEBHOOK, "hook", Map.of()));
        DeliveryRecord unknown = delivery.enqueue(alert(), new ActionSpec(ActionType.WEBHOOK, "missing", Map.of()));
        DeliveryRecord mismatch = delivery.enqueue(alert(), new ActionSpec(ActionType.SLACK, "hook", Map.of()));
        delivery.processDue(10);
        assertThat(store.find(perm.id()).orElseThrow()).satisfies(x -> { assertThat(x.status()).isEqualTo(DeliveryStatus.DEAD_LETTER); assertThat(x.attempts()).isEqualTo(1); });
        assertThat(store.find(unknown.id()).orElseThrow().lastError()).contains("unknown connector");
        assertThat(store.find(mismatch.id()).orElseThrow().lastError()).contains("SLACK");

        hook.script.add(THROW); // connector throws -> retryable, never propagates
        DeliveryRecord boom = delivery.enqueue(alert(), new ActionSpec(ActionType.WEBHOOK, "hook", Map.of()));
        assertThat(delivery.processDue(10)).singleElement().satisfies(x -> {
            assertThat(x.id()).isEqualTo(boom.id());
            assertThat(x.status()).isEqualTo(DeliveryStatus.PENDING);
            assertThat(x.lastError()).contains("IllegalStateException");
        });
    }

    // ---------------------------------------------------------------- end to end

    @Test void endToEndEventToSignedWebhookAndNotifiedStatus() {
        ConnectorsTest.FakeHttp http = new ConnectorsTest.FakeHttp();
        byte[] secret = "pipeline-secret-pipeline-secret-!!".getBytes(StandardCharsets.UTF_8);
        ConnectorRegistry reg = new ConnectorRegistry()
                .register(new WebhookConnector("default-webhook", URI.create("https://hooks.example.com/oc"), secret, http, clock));
        DeliveryService ds = new DeliveryService(new InMemoryDeliveryStore(), reg, BACKOFF, clock);
        RuleEngine engine = new RuleEngine(EngineConfig.defaults());
        engine.register(Fx.rule(header("big") + """
                conditions: { logic: AND, rules: [ { field: transaction.amount, operator: GREATER_THAN, value: 1000 } ] }
                dedupKey: "{{rule.id}}:{{event.transaction.from}}"
                actions: [ { type: WEBHOOK, connectorId: default-webhook } ]
                """));
        AlertingPipeline p = new AlertingPipeline(engine, alerts, ds);

        AlertingPipeline.Outcome o = p.onEvent(Fx.transfer("e1", T0, "TA", "TB", "5000"));
        assertThat(o.created()).singleElement().satisfies(a -> assertThat(a.status()).isEqualTo(AlertStatus.PENDING));
        assertThat(o.enqueued()).hasSize(1);
        assertThat(p.onEvent(Fx.transfer("e2", T0.plusSeconds(30), "TA", "TB", "7000")).grouped()).hasSize(1); // no 2nd notification
        assertThat(p.dispatch(10)).singleElement().satisfies(d -> assertThat(d.status()).isEqualTo(DeliveryStatus.DELIVERED));
        Alert a = alerts.find(o.created().getFirst().id()).orElseThrow();
        assertThat(a.status()).isEqualTo(AlertStatus.NOTIFIED);
        assertThat(a.occurrenceCount()).isEqualTo(2);
        assertThat(http.calls).hasSize(1);
        assertThat(WebhookSignature.verify(secret, http.calls.getFirst().headers().get(WebhookSignature.HEADER),
                http.calls.getFirst().body(), clock.now, Duration.ofMinutes(5))).isTrue();

        // reorg of a matched event annotates the alert
        p.onEvent(Fx.removed(Fx.transfer("e1", T0, "TA", "TB", "5000")));
        assertThat(alerts.find(a.id()).orElseThrow().comments()).anyMatch(c -> c.author().equals("system:reorg"));
    }

    @Test void deadLetteredDeliveryIsRecordedOnTheAlert() {
        ConnectorsTest.FakeHttp http = new ConnectorsTest.FakeHttp();
        http.status = 404;
        ConnectorRegistry reg = new ConnectorRegistry().register(new WebhookConnector("default-webhook",
                URI.create("https://hooks.example.com/gone"), "pipeline-secret-pipeline-secret-!!".getBytes(StandardCharsets.UTF_8), http, clock));
        RuleEngine engine = new RuleEngine(EngineConfig.defaults());
        engine.register(Fx.rule(header("big") + """
                conditions: { logic: AND, rules: [ { field: transaction.amount, operator: GREATER_THAN, value: 1000 } ] }
                actions: [ { type: WEBHOOK, connectorId: default-webhook } ]
                """));
        AlertingPipeline p = new AlertingPipeline(engine, alerts, new DeliveryService(new InMemoryDeliveryStore(), reg, BACKOFF, clock));
        String id = p.onEvent(Fx.transfer("e1", T0, "TA", "TB", "5000")).created().getFirst().id();
        assertThat(p.dispatch(10).getFirst().status()).isEqualTo(DeliveryStatus.DEAD_LETTER);
        Alert a = alerts.find(id).orElseThrow();
        assertThat(a.status()).isEqualTo(AlertStatus.PENDING); // still unacknowledged -> subject to escalation
        assertThat(a.comments()).singleElement().satisfies(c -> {
            assertThat(c.author()).isEqualTo("system:delivery");
            assertThat(c.text()).contains("HTTP 404");
        });
    }

    @Test void internalEventLoopTerminates() {
        // rule A fires on transfers and emits an internal event; rule B and A both listen to DETECTION_SIGNAL too
        List<ChainEvent> bus = new ArrayList<>();
        ConnectorRegistry reg = new ConnectorRegistry().register(new InternalEventConnector("bus", bus::add, clock));
        DeliveryService ds = new DeliveryService(new InMemoryDeliveryStore(), reg, BACKOFF, clock);
        RuleEngine engine = new RuleEngine(EngineConfig.defaults());
        String anyEvent = "conditions: { logic: OR, rules: [ { field: transaction.amount, operator: EXISTS }, { field: alert.id, operator: EXISTS } ] }\n"
                + "cooldown: 0s\nactions: [ { type: INTERNAL_EVENT, connectorId: bus } ]\n";
        engine.register(Fx.rule(header("rule-a") + anyEvent.replace("0s", "{ duration: 0, unit: SECONDS }")));
        engine.register(Fx.rule(header("rule-b") + anyEvent.replace("0s", "{ duration: 0, unit: SECONDS }")));
        AlertingPipeline p = new AlertingPipeline(engine, alerts, ds);

        p.onEvent(Fx.transfer("seed", T0, "TA", "TB", "1"));  // A and B fire (hop 0)
        int rounds = 0;
        while (true) {
            p.dispatch(100);
            if (bus.isEmpty() || ++rounds > 10) break;
            List<ChainEvent> batch = new ArrayList<>(bus);
            bus.clear();
            batch.forEach(p::onEvent);
        }
        assertThat(rounds).isLessThanOrEqualTo(3); // bounded: hop 1 is processed by the *other* rule only, hop 2 suppressed
        // hop 0: A, B ; hop 1: B on A's event, A on B's event ; hop 2: suppressed
        assertThat(alerts.repository().findAll()).hasSize(4);
        assertThat(engine.metrics().loopSuppressed()).isGreaterThanOrEqualTo(2);
    }
}
