package io.openchain.alerting.alert;

import static io.openchain.alerting.Fx.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.openchain.alerting.engine.RuleMatch;
import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.model.Network;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AlertServiceTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-06-01T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    MutableClock clock;
    AlertService service;

    @BeforeEach void setUp() {
        clock = new MutableClock();
        AtomicInteger n = new AtomicInteger();
        service = new AlertService(new InMemoryAlertRepository(), clock, new EscalationPolicy(Duration.ofMinutes(30), 2),
                () -> "alert-" + n.incrementAndGet());
    }

    static RuleMatch match(String dedupKey, Instant t, String eventId, Severity sev) {
        return new RuleMatch("rule-a", "1.0.0", "Rule A", sev, EvidenceLevel.RISK_SIGNAL, Network.TRON, "tron|TA", dedupKey,
                eventId, t, List.of(eventId), Map.of("kind", "STATELESS"), List.of(), 0);
    }

    @Test void newMatchCreatesDetectedAlertWithHistory() {
        AlertService.MatchOutcome o = service.onMatch(match("k1", T0, "e1", Severity.HIGH), Duration.ofMinutes(15));
        assertThat(o.created()).isTrue();
        Alert a = o.alert();
        assertThat(a.status()).isEqualTo(AlertStatus.DETECTED);
        assertThat(a.priority()).isEqualTo(Priority.P2);
        assertThat(a.occurrenceCount()).isEqualTo(1);
        assertThat(a.firstSeen()).isEqualTo(T0);
        assertThat(a.evidenceLevel()).isEqualTo(EvidenceLevel.RISK_SIGNAL);
        assertThat(a.history()).singleElement().satisfies(h -> {
            assertThat(h.to()).isEqualTo(AlertStatus.DETECTED);
            assertThat(h.actor()).isEqualTo("system");
            assertThat(h.at()).isEqualTo(clock.now);
        });
    }

    @Test void sameDedupKeyWithinCooldownIsGrouped() {
        Alert first = service.onMatch(match("k1", T0, "e1", Severity.HIGH), Duration.ofMinutes(15)).alert();
        AlertService.MatchOutcome second = service.onMatch(match("k1", T0.plus(Duration.ofMinutes(10)), "e2", Severity.HIGH), Duration.ofMinutes(15));
        assertThat(second.outcome()).isEqualTo(AlertService.Outcome.GROUPED);
        assertThat(second.alert().id()).isEqualTo(first.id());
        assertThat(second.alert().occurrenceCount()).isEqualTo(2);
        assertThat(second.alert().matchedEventIds()).containsExactly("e1", "e2");
        assertThat(second.alert().lastSeen()).isEqualTo(T0.plus(Duration.ofMinutes(10)));
        // a different dedup key is a different alert
        assertThat(service.onMatch(match("k2", T0, "e3", Severity.HIGH), Duration.ofMinutes(15)).created()).isTrue();
    }

    @Test void afterCooldownANewAlertIsCreated() {
        service.onMatch(match("k1", T0, "e1", Severity.HIGH), Duration.ofMinutes(15));
        service.onMatch(match("k1", T0.plus(Duration.ofMinutes(10)), "e2", Severity.HIGH), Duration.ofMinutes(15));
        // cooldown is measured from lastSeen (sliding): 24 min after first, 14 after last -> still grouped
        assertThat(service.onMatch(match("k1", T0.plus(Duration.ofMinutes(24)), "e3", Severity.HIGH), Duration.ofMinutes(15)).created()).isFalse();
        assertThat(service.onMatch(match("k1", T0.plus(Duration.ofMinutes(40)), "e4", Severity.HIGH), Duration.ofMinutes(15)).created()).isTrue();
        assertThat(service.repository().findAll()).hasSize(2);
    }

    @Test void zeroCooldownNeverGroupsAndTerminalAlertsAreNotReopened() {
        assertThat(service.onMatch(match("k1", T0, "e1", Severity.LOW), Duration.ZERO).created()).isTrue();
        assertThat(service.onMatch(match("k1", T0, "e2", Severity.LOW), Duration.ZERO).created()).isTrue();

        Alert a = service.onMatch(match("k9", T0, "x1", Severity.LOW), Duration.ofHours(1)).alert();
        service.transition(a.id(), AlertStatus.FALSE_POSITIVE, "analyst@soc", "known exchange wallet");
        AlertService.MatchOutcome again = service.onMatch(match("k9", T0.plusSeconds(60), "x2", Severity.LOW), Duration.ofHours(1));
        assertThat(again.created()).isTrue();
        assertThat(again.alert().id()).isNotEqualTo(a.id());
    }

    @Test void fullLifecycleRecordsActorsAndTimestamps() {
        Alert a = service.onMatch(match("k1", T0, "e1", Severity.CRITICAL), Duration.ofMinutes(15)).alert();
        service.transition(a.id(), AlertStatus.PENDING, "system", "dispatching");
        clock.advance(Duration.ofSeconds(5));
        service.transition(a.id(), AlertStatus.NOTIFIED, "system", "delivered");
        clock.advance(Duration.ofMinutes(3));
        service.transition(a.id(), AlertStatus.ACKNOWLEDGED, "alice", null);
        service.assign(a.id(), "alice", "lead");
        service.comment(a.id(), "alice", "looking at counterparties");
        service.transition(a.id(), AlertStatus.INVESTIGATING, "alice", "tracing funds");
        Alert done = service.transition(a.id(), AlertStatus.RESOLVED, "alice", "benign treasury rebalancing");
        assertThat(done.status()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(done.assignee()).isEqualTo("alice");
        assertThat(done.comments()).singleElement().satisfies(c -> assertThat(c.text()).contains("counterparties"));
        assertThat(done.history()).extracting(Alert.HistoryEntry::to).containsExactly(AlertStatus.DETECTED, AlertStatus.PENDING,
                AlertStatus.NOTIFIED, AlertStatus.ACKNOWLEDGED, AlertStatus.ACKNOWLEDGED, AlertStatus.INVESTIGATING, AlertStatus.RESOLVED);
        assertThat(done.history().getLast().actor()).isEqualTo("alice");
        assertThat(done.history().get(3).at()).isEqualTo(clock.now);
        assertThat(service.repository().findOpenByDedupKey("rule-a", "k1")).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(AlertStatus.class)
    void invalidTransitionsThrow(AlertStatus from) {
        for (AlertStatus to : AlertStatus.values()) {
            boolean allowed = from.canTransitionTo(to);
            boolean expected = switch (from) {
                case DETECTED -> EnumSet.of(AlertStatus.PENDING, AlertStatus.RESOLVED, AlertStatus.FALSE_POSITIVE).contains(to);
                case PENDING -> EnumSet.of(AlertStatus.NOTIFIED, AlertStatus.ACKNOWLEDGED, AlertStatus.RESOLVED, AlertStatus.FALSE_POSITIVE).contains(to);
                case NOTIFIED -> EnumSet.of(AlertStatus.ACKNOWLEDGED, AlertStatus.RESOLVED, AlertStatus.FALSE_POSITIVE).contains(to);
                case ACKNOWLEDGED -> EnumSet.of(AlertStatus.INVESTIGATING, AlertStatus.RESOLVED, AlertStatus.FALSE_POSITIVE).contains(to);
                case INVESTIGATING -> EnumSet.of(AlertStatus.RESOLVED, AlertStatus.FALSE_POSITIVE).contains(to);
                case RESOLVED, FALSE_POSITIVE -> false;
            };
            assertThat(allowed).as(from + " -> " + to).isEqualTo(expected);
        }
    }

    @Test void illegalTransitionIsRejected() {
        Alert a = service.onMatch(match("k1", T0, "e1", Severity.HIGH), Duration.ofMinutes(15)).alert();
        assertThatThrownBy(() -> service.transition(a.id(), AlertStatus.INVESTIGATING, "bob", null))
                .isInstanceOf(IllegalAlertTransitionException.class).hasMessageContaining("DETECTED -> INVESTIGATING");
        service.transition(a.id(), AlertStatus.RESOLVED, "bob", "dup");
        assertThatThrownBy(() -> service.transition(a.id(), AlertStatus.ACKNOWLEDGED, "bob", null))
                .isInstanceOf(IllegalAlertTransitionException.class);
        assertThatThrownBy(() -> service.transition(a.id(), AlertStatus.RESOLVED, " ", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.transition("nope", AlertStatus.RESOLVED, "bob", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void unacknowledgedAlertsAreEscalated() {
        Alert a = service.onMatch(match("k1", T0, "e1", Severity.MEDIUM), Duration.ofMinutes(15)).alert();
        Alert acked = service.onMatch(match("k2", T0, "e2", Severity.MEDIUM), Duration.ofMinutes(15)).alert();
        service.transition(acked.id(), AlertStatus.PENDING, "system", null);
        service.transition(acked.id(), AlertStatus.ACKNOWLEDGED, "bob", null);
        assertThat(a.priority()).isEqualTo(Priority.P3);

        clock.advance(Duration.ofMinutes(29));
        assertThat(service.escalateOverdue()).isEmpty();
        clock.advance(Duration.ofMinutes(1));
        assertThat(service.escalateOverdue()).singleElement().satisfies(x -> {
            assertThat(x.id()).isEqualTo(a.id());
            assertThat(x.priority()).isEqualTo(Priority.P2);
            assertThat(x.history().getLast().reason()).contains("P3 -> P2");
        });
        assertThat(service.escalateOverdue()).isEmpty(); // timer restarts at each escalation
        clock.advance(Duration.ofMinutes(30));
        assertThat(service.escalateOverdue()).singleElement().satisfies(x -> assertThat(x.priority()).isEqualTo(Priority.P1));
        clock.advance(Duration.ofHours(5));
        assertThat(service.escalateOverdue()).isEmpty(); // maxEscalations = 2
    }

    @Test void reorgRetractionAnnotatesAlerts() {
        Alert a = service.onMatch(match("k1", T0, "e1", Severity.HIGH), Duration.ofMinutes(15)).alert();
        assertThat(service.onRetraction("e1")).singleElement().satisfies(x -> {
            assertThat(x.id()).isEqualTo(a.id());
            assertThat(x.comments().getLast().author()).isEqualTo("system:reorg");
        });
        assertThat(service.onRetraction("unknown")).isEmpty();
    }

    @Test void matchedEventIdsAreBounded() {
        service.onMatch(match("k1", T0, "e0", Severity.HIGH), Duration.ofDays(1));
        for (int i = 1; i < 700; i++) service.onMatch(match("k1", T0.plusSeconds(i), "e" + i, Severity.HIGH), Duration.ofDays(1));
        Alert a = service.repository().findAll().getFirst();
        assertThat(a.occurrenceCount()).isEqualTo(700);
        assertThat(a.matchedEventIds()).hasSize(Alert.MAX_EVENT_IDS).endsWith("e699");
    }
}
