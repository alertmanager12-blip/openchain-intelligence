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

    // ---- code review I8 (2026-11-13), verification: a signal evicted from memory and detected again is re-emitted with
    // the same id ("signal:<id>"); the engine's dedup cache (200,000 ids, ~50 min at chain-wide volume) has forgotten it.

    static ChainEvent signal(String id, long sec, String wallet) {
        return Fx.event("signal:" + id, io.openchain.core.model.Network.TRON, ChainEvent.EventType.DETECTION_SIGNAL, T0.plusSeconds(sec),
                Map.of("signal.type", "INDIRECT_EXPOSURE", "signal.confidence", new java.math.BigDecimal("0.5"), "wallet.address", wallet));
    }

    /** Engine whose dedup cache keeps one id only: the re-emitted signal is no longer known as a duplicate. */
    static RuleEngine forgetfulEngine() { return new RuleEngine(EngineConfig.defaults().withDedup(1, Duration.ofHours(24))); }

    @Test void reEmittedSignalDoesNotDuplicateAStatelessAlert() {
        RuleEngine engine = forgetfulEngine();
        engine.register(Fx.rule(header("exposure") + """
                scope: { eventTypes: [DETECTION_SIGNAL] }
                conditions: { logic: AND, rules: [ { field: signal.type, operator: IN, value: [SANCTIONED_EXPOSURE, INDIRECT_EXPOSURE] } ] }
                cooldown: 6h
                dedupKey: "{{rule.id}}:{{event.wallet.address}}"
                actions: [ { type: WEBHOOK, connectorId: hook } ]
                """));
        AlertingPipeline p = new AlertingPipeline(engine, alerts, delivery);
        p.onEvent(signal("x", 0, "TW"));
        p.onEvent(Fx.transfer("other", T0.plusSeconds(1), "TA", "TB", "1")); // pushes signal:x out of the dedup cache
        p.dispatch(10);
        int sent = hook.received.size();
        var again = p.onEvent(signal("x", 3600, "TW")); // same signal, detected again one hour later
        assertThat(again.created()).isEmpty();
        assertThat(alerts.repository().findAll()).hasSize(1);
        assertThat(p.dispatch(10)).isEmpty();
        assertThat(hook.received).hasSize(sent);
    }

    @Test void reEmittedSignalIsNotCountedTwiceByAnAggregation() {
        RuleEngine engine = forgetfulEngine();
        engine.register(Fx.rule(header("many-signals") + """
                scope: { eventTypes: [DETECTION_SIGNAL] }
                conditions: { logic: AND, rules: [ { field: signal.type, operator: EQUALS, value: INDIRECT_EXPOSURE } ] }
                aggregation: { window: 24h, groupBy: wallet.address, function: COUNT, operator: GREATER_THAN, threshold: 2 }
                actions: [ { type: WEBHOOK, connectorId: hook } ]
                """));
        AlertingPipeline p = new AlertingPipeline(engine, alerts, delivery);
        p.onEvent(signal("s1", 0, "TW"));
        p.onEvent(signal("s2", 60, "TW"));
        p.onEvent(Fx.transfer("other", T0.plusSeconds(61), "TA", "TB", "1"));
        // two distinct signals only: s1 re-emitted must not make three
        assertThat(p.onEvent(signal("s1", 120, "TW")).created()).isEmpty();
        assertThat(alerts.repository().findAll()).isEmpty();
        assertThat(engine.metrics().windowDuplicates()).isEqualTo(1);
        // a genuinely new third signal still triggers
        assertThat(p.onEvent(signal("s3", 180, "TW")).created()).singleElement()
                .satisfies(a -> assertThat(a.matchedEventIds()).containsExactly("signal:s1", "signal:s2", "signal:s3"));
    }

    // ---- code review I4 (2026-11-13): an alert announced as withdrawn that has valid evidence again is announced too

    AlertingPipeline bigPipeline() {
        RuleEngine engine = new RuleEngine(EngineConfig.defaults());
        engine.register(Fx.rule(header("big") + """
                conditions: { logic: AND, rules: [ { field: transaction.amount, operator: GREATER_THAN, value: 1000 } ] }
                dedupKey: "{{rule.id}}:{{event.transaction.from}}"
                actions: [ { type: WEBHOOK, connectorId: hook } ]
                """));
        return new AlertingPipeline(engine, alerts, delivery);
    }

    static List<String> events(List<DeliveryRecord> ds) { return ds.stream().map(d -> d.notification().event()).toList(); }

    @Test void reincludedEvidenceOfAWithdrawnAlertIsAnnouncedOnce() {
        AlertingPipeline p = bigPipeline();
        Alert a = p.onEvent(Fx.transfer("e1", T0, "TA", "TB", "5000")).created().getFirst();
        assertThat(events(p.dispatch(10))).containsExactly(Notification.EVENT_OPENED);
        p.onEvent(Fx.removed(Fx.transfer("e1", T0, "TA", "TB", "5000")));
        assertThat(events(p.dispatch(10))).containsExactly(Notification.EVENT_RETRACTED);

        p.onEvent(Fx.transfer("e1", T0, "TA", "TB", "5000")); // back on the canonical chain
        assertThat(alerts.find(a.id()).orElseThrow().evidenceState()).isEqualTo(Alert.EvidenceState.VALID);
        assertThat(p.dispatch(10)).singleElement().satisfies(d -> {
            assertThat(d.notification().event()).isEqualTo(Notification.EVENT_REINSTATED);
            assertThat(d.notification().alertId()).isEqualTo(a.id());
            assertThat(NotificationFormatter.text(d.notification(), 4000)).startsWith("EVIDENCE REINSTATED");
        });
        p.onEvent(Fx.transfer("e1", T0, "TA", "TB", "5000")); // repeated: already valid, nothing announced
        assertThat(p.dispatch(10)).isEmpty();
        assertThat(alerts.repository().findAll()).hasSize(1);
    }

    @Test void newMatchGroupedIntoAWithdrawnAlertMakesItValidAgainAndIsAnnounced() {
        AlertingPipeline p = bigPipeline();
        Alert a = p.onEvent(Fx.transfer("e1", T0, "TA", "TB", "5000")).created().getFirst();
        p.onEvent(Fx.removed(Fx.transfer("e1", T0, "TA", "TB", "5000")));
        p.dispatch(10);
        assertThat(alerts.find(a.id()).orElseThrow().evidenceState()).isEqualTo(Alert.EvidenceState.WITHDRAWN);

        var o = p.onEvent(Fx.transfer("e2", T0.plusSeconds(60), "TA", "TB", "7000")); // same sender, within the cooldown
        assertThat(o.created()).isEmpty();
        Alert after = alerts.find(a.id()).orElseThrow();
        assertThat(after.evidenceState()).isEqualTo(Alert.EvidenceState.PARTIALLY_WITHDRAWN); // e1 withdrawn, e2 valid
        assertThat(after.history().getLast().reason()).contains("valid evidence again");
        assertThat(events(p.dispatch(10))).containsExactly(Notification.EVENT_REINSTATED);
    }

    // ---- code review I3 (2026-11-13): aggregation alerts re-evaluate their threshold after a retraction

    static final String VOLUME = """
            conditions: { logic: AND, rules: [ { field: transaction.amount, operator: GREATER_THAN, value: 0 } ] }
            aggregation: { window: 1h, groupBy: transaction.from, function: SUM, field: transaction.amount, operator: GREATER_THAN, threshold: "100000" }
            actions: [ { type: WEBHOOK, connectorId: hook } ]
            """;

    AlertingPipeline volumePipeline(RuleEngine engine) {
        engine.register(Fx.rule(header("volume") + VOLUME));
        return new AlertingPipeline(engine, alerts, delivery);
    }

    /** The remaining events no longer reach the threshold: the alert has no valid evidence, and the channels are told. */
    @Test void aggregationAlertWhoseThresholdIsNoLongerMetIsWithdrawn() {
        AlertingPipeline p = volumePipeline(new RuleEngine(EngineConfig.defaults()));
        p.onEvent(Fx.transfer("a", T0, "TA", "TB", "60000"));
        Alert a = p.onEvent(Fx.transfer("b", T0.plusSeconds(1800), "TA", "TB", "50000")).created().getFirst();
        p.dispatch(10);
        int sent = hook.received.size();

        p.onEvent(Fx.removed(Fx.transfer("a", T0, "TA", "TB", "60000")));
        Alert after = alerts.find(a.id()).orElseThrow();
        assertThat(after.evidenceState()).isEqualTo(Alert.EvidenceState.WITHDRAWN); // b remains, but 50000 is not > 100000
        assertThat(after.withdrawnEventIds()).containsExactly("a");
        assertThat(after.history().getLast().reason()).contains("no longer met", "SUM 50000 over 1 event(s)", "threshold GREATER_THAN 100000");
        assertThat(alerts.thresholdNoLongerMet()).isEqualTo(1);
        assertThat(p.dispatch(10)).singleElement().satisfies(d -> assertThat(d.notification().event()).isEqualTo(Notification.EVENT_RETRACTED));
        assertThat(hook.received).hasSize(sent + 1);
    }

    /** Cited events were withdrawn but the remaining ones still pass: the alert stays valid, with the new value. */
    @Test void aggregationAlertWhoseThresholdIsStillMetStaysValid() {
        AlertingPipeline p = volumePipeline(new RuleEngine(EngineConfig.defaults()));
        p.onEvent(Fx.transfer("a", T0, "TA", "TB", "60000"));
        Alert a = p.onEvent(Fx.transfer("b", T0.plusSeconds(1800), "TA", "TB", "50000")).created().getFirst();
        p.onEvent(Fx.transfer("c", T0.plusSeconds(2400), "TA", "TB", "70000")); // grouped into the same alert
        p.dispatch(10);

        p.onEvent(Fx.removed(Fx.transfer("a", T0, "TA", "TB", "60000")));
        Alert after = alerts.find(a.id()).orElseThrow();
        assertThat(after.evidenceState()).isEqualTo(Alert.EvidenceState.VALID); // b + c = 120000 > 100000
        assertThat(after.withdrawnEventIds()).containsExactly("a");
        assertThat(after.history().getLast().reason()).contains("still met", "SUM 120000 over 2 event(s)");
        assertThat(p.dispatch(10)).isEmpty();
    }

    /** After a restart the engine no longer holds the windows: nothing is concluded, and the history says so. */
    @Test void aggregationAlertAfterARestartIsNotReverifiedAndSaysSo() {
        AlertingPipeline p1 = volumePipeline(new RuleEngine(EngineConfig.defaults()));
        p1.onEvent(Fx.transfer("x", T0, "TX", "TB", "60000"));
        Alert a = p1.onEvent(Fx.transfer("y", T0.plusSeconds(1800), "TX", "TB", "50000")).created().getFirst();

        AlertingPipeline p2 = volumePipeline(new RuleEngine(EngineConfig.defaults())); // restarted: empty engine state
        p2.onEvent(Fx.removed(Fx.transfer("x", T0, "TX", "TB", "60000")));
        Alert after = alerts.find(a.id()).orElseThrow();
        assertThat(after.evidenceState()).isEqualTo(Alert.EvidenceState.PARTIALLY_WITHDRAWN); // count rule, as before
        assertThat(after.history().getLast().reason()).contains("1 of 2 matched event(s) remain", "threshold not re-verifiable");
        assertThat(alerts.thresholdUnverifiable()).isEqualTo(1);
        assertThat(alerts.thresholdNoLongerMet()).isZero();
    }

    /** P0 reorg: alert.retracted goes out once, when the last valid evidence is withdrawn, through the outbox. */
    @Test void alertRetractedIsDeliveredOnceWhenNoValidEvidenceRemains() {
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
        Alert a = p.onEvent(Fx.transfer("e1", T0, "TA", "TB", "5000")).created().getFirst();
        p.onEvent(Fx.transfer("e2", T0.plusSeconds(30), "TA", "TB", "7000"));
        p.dispatch(10);
        assertThat(http.calls).hasSize(1);

        assertThat(p.onEvent(Fx.removed(Fx.transfer("e1", T0, "TA", "TB", "5000"))).enqueued()).isEmpty();
        assertThat(alerts.find(a.id()).orElseThrow().evidenceState()).isEqualTo(Alert.EvidenceState.PARTIALLY_WITHDRAWN);
        assertThat(p.dispatch(10)).isEmpty(); // partial: nothing announced

        p.onEvent(Fx.removed(Fx.transfer("e2", T0.plusSeconds(30), "TA", "TB", "7000")));
        assertThat(alerts.find(a.id()).orElseThrow().evidenceState()).isEqualTo(Alert.EvidenceState.WITHDRAWN);
        assertThat(p.dispatch(10)).singleElement().satisfies(d -> {
            assertThat(d.status()).isEqualTo(DeliveryStatus.DELIVERED);
            assertThat(d.notification().event()).isEqualTo(Notification.EVENT_RETRACTED);
        });
        assertThat(http.calls).hasSize(2);
        assertThat(http.calls.getLast().headers().get(WebhookSignature.EVENT_HEADER)).isEqualTo("alert.retracted");
        assertThat(new String(http.calls.getLast().body(), StandardCharsets.UTF_8)).contains("\"event\":\"alert.retracted\"");

        p.onEvent(Fx.removed(Fx.transfer("e2", T0.plusSeconds(30), "TA", "TB", "7000"))); // repeated notice
        assertThat(p.dispatch(10)).isEmpty();
        assertThat(http.calls).hasSize(2);
        assertThat(alerts.find(a.id()).orElseThrow().status()).isEqualTo(AlertStatus.NOTIFIED); // kept, status unchanged
    }

    /**
     * Code review I1 (2026-11-13): after a restart the rule engine's dedup cache is empty, so the engine ignores the
     * retraction of an event indexed before the restart. The stored alert must still have its evidence withdrawn and
     * alert.retracted must still go out.
     */
    @Test void retractionAfterARestartStillWithdrawsTheStoredAlert() {
        ConnectorsTest.FakeHttp http = new ConnectorsTest.FakeHttp();
        byte[] secret = "pipeline-secret-pipeline-secret-!!".getBytes(StandardCharsets.UTF_8);
        ConnectorRegistry reg = new ConnectorRegistry()
                .register(new WebhookConnector("default-webhook", URI.create("https://hooks.example.com/oc"), secret, http, clock));
        DeliveryService ds = new DeliveryService(new InMemoryDeliveryStore(), reg, BACKOFF, clock);
        String rule = header("big") + """
                conditions: { logic: AND, rules: [ { field: transaction.amount, operator: GREATER_THAN, value: 1000 } ] }
                dedupKey: "{{rule.id}}:{{event.transaction.from}}"
                actions: [ { type: WEBHOOK, connectorId: default-webhook } ]
                """;
        RuleEngine before = new RuleEngine(EngineConfig.defaults());
        before.register(Fx.rule(rule));
        Alert a = new AlertingPipeline(before, alerts, ds).onEvent(Fx.transfer("e1", T0, "TA", "TB", "5000")).created().getFirst();

        // restart: a new engine (empty dedup cache), the same persisted alerts and outbox
        RuleEngine after = new RuleEngine(EngineConfig.defaults());
        after.register(Fx.rule(rule));
        AlertingPipeline p = new AlertingPipeline(after, alerts, ds);
        p.dispatch(10);
        int sent = http.calls.size();

        p.onEvent(Fx.removed(Fx.transfer("e1", T0, "TA", "TB", "5000")));
        assertThat(alerts.find(a.id()).orElseThrow().evidenceState()).isEqualTo(Alert.EvidenceState.WITHDRAWN);
        assertThat(p.dispatch(10)).singleElement().satisfies(d -> assertThat(d.notification().event()).isEqualTo(Notification.EVENT_RETRACTED));
        assertThat(http.calls).hasSize(sent + 1);

        p.onEvent(Fx.removed(Fx.transfer("e1", T0, "TA", "TB", "5000"))); // repeated notice: idempotent
        assertThat(p.dispatch(10)).isEmpty();
    }

    /**
     * Code review I18 (2026-11-13): deliveries were attempted one after the other, so one unreachable webhook (seconds of
     * timeout per attempt) delayed every other channel. Connectors are now served in parallel.
     */
    @Test void aBlockedConnectorDoesNotDelayTheOthers() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1), fastSent = new java.util.concurrent.CountDownLatch(1);
        registry.register(new NotificationConnector() {
            @Override public String id() { return "blackhole"; }
            @Override public ActionType type() { return ActionType.WEBHOOK; }
            @Override public DeliveryResult send(Notification n) {
                try { release.await(10, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return DeliveryResult.retry(0, "timeout");
            }
        });
        registry.register(new NotificationConnector() {
            @Override public String id() { return "telegram"; }
            @Override public ActionType type() { return ActionType.TELEGRAM; }
            @Override public DeliveryResult send(Notification n) { fastSent.countDown(); return DeliveryResult.ok(200); }
        });
        delivery.enqueue(alert(), new ActionSpec(ActionType.WEBHOOK, "blackhole", Map.of()));   // due first
        DeliveryRecord fast = delivery.enqueue(alert(), new ActionSpec(ActionType.TELEGRAM, "telegram", Map.of()));
        Thread pass = Thread.ofVirtual().start(() -> delivery.processDue(10));
        try {
            assertThat(fastSent.await(3, java.util.concurrent.TimeUnit.SECONDS)).as("sent while the other connector is still blocked").isTrue();
        } finally {
            release.countDown();
            pass.join(5_000);
        }
        assertThat(store.find(fast.id()).orElseThrow().status()).isEqualTo(DeliveryStatus.DELIVERED);
    }

    /** A failing connector gets a bounded number of attempts per pass; its other deliveries wait for the next pass, untouched. */
    @Test void aFailingConnectorIsAttemptedABoundedNumberOfTimesPerPass() {
        for (int i = 0; i < 6; i++) hook.script.add(DeliveryResult.retry(0, "timeout"));
        List<DeliveryRecord> queued = new ArrayList<>();
        for (int i = 0; i < 6; i++) queued.add(delivery.enqueue(alert(), new ActionSpec(ActionType.WEBHOOK, "hook", Map.of())));
        delivery.processDue(10);
        assertThat(hook.received).hasSize(DeliveryService.MAX_CONSECUTIVE_FAILURES_PER_PASS);
        long untouched = queued.stream().map(r -> store.find(r.id()).orElseThrow())
                .filter(r -> r.attempts() == 0 && r.status() == DeliveryStatus.PENDING).count();
        assertThat(untouched).isEqualTo(6 - DeliveryService.MAX_CONSECUTIVE_FAILURES_PER_PASS);
        // nothing is lost: once the connector answers again, a later pass delivers them
        hook.script.clear();
        clock.now = clock.now.plus(Duration.ofMinutes(5));
        delivery.processDue(10);
        assertThat(queued).allSatisfy(r -> assertThat(store.find(r.id()).orElseThrow().status()).isEqualTo(DeliveryStatus.DELIVERED));
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
