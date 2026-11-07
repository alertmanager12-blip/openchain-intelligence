package io.openchain.alerting.alert;

import io.openchain.alerting.alert.Alert.Comment;
import io.openchain.alerting.alert.Alert.HistoryEntry;
import io.openchain.alerting.engine.RuleMatch;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Alert lifecycle, deduplication, cooldown grouping and escalation.
 * <p>Grouping: a match whose (rule, dedup key) has an open alert last seen within the rule cooldown (event time)
 * increments that alert's {@code occurrenceCount} instead of creating (and notifying) a new alert.
 */
public final class AlertService {

    public enum Outcome { CREATED, GROUPED }

    public record MatchOutcome(Outcome outcome, Alert alert) {
        public boolean created() { return outcome == Outcome.CREATED; }
    }

    public static final String SYSTEM = "system";
    private static final int MAX_TEXT = 4000;

    private final AlertRepository repository;
    private final Clock clock;
    private final EscalationPolicy escalation;
    private final Supplier<String> ids;

    public AlertService(AlertRepository repository, Clock clock, EscalationPolicy escalation, Supplier<String> ids) {
        this.repository = repository;
        this.clock = clock;
        this.escalation = escalation;
        this.ids = ids;
    }

    public AlertService(AlertRepository repository, Clock clock) {
        this(repository, clock, EscalationPolicy.defaults(), () -> UUID.randomUUID().toString());
    }

    public synchronized MatchOutcome onMatch(RuleMatch m, Duration cooldown) {
        Instant now = clock.instant();
        // Replay of an already-alerted event (re-indexing after a restart with a persistent repository): the alert
        // that holds this triggering event is returned unchanged, so no duplicate alert or notification is produced.
        if (m.triggeringEventId() != null) {
            Optional<Alert> replay = repository.findByMatchedEventId(m.triggeringEventId()).stream()
                    .filter(a -> a.ruleId().equals(m.ruleId()) && Objects.equals(a.dedupKey(), m.dedupKey()))
                    .findFirst();
            if (replay.isPresent()) {
                Alert r = replay.get();
                // alerts stored before subjects existed get theirs from the replayed (re-indexed) trigger
                if ((r.subject() == null || r.subject().eventId() == null) && m.subject() != null && m.triggeringEventId().equals(r.matchedEventIds().isEmpty() ? null
                        : r.matchedEventIds().getLast())) {
                    r = repository.save(r.toBuilder().subject(m.subject()).build());
                }
                return new MatchOutcome(Outcome.GROUPED, r);
            }
        }
        Optional<Alert> open = repository.findOpenByDedupKey(m.ruleId(), m.dedupKey());
        if (open.isPresent() && withinCooldown(open.get(), m.eventTime(), cooldown)) {
            Alert a = open.get();
            Alert grouped = a.toBuilder()
                    .occurrenceCount(a.occurrenceCount() + 1)
                    .addEventIds(m.matchedEventIds())
                    .firstSeen(min(a.firstSeen(), m.eventTime()))
                    .lastSeen(max(a.lastSeen(), m.eventTime()))
                    .subject(m.subject() != null && !m.eventTime().isBefore(a.lastSeen()) ? m.subject() : a.subject())
                    .updatedAt(now)
                    .build();
            return new MatchOutcome(Outcome.GROUPED, repository.save(grouped));
        }
        Alert created = Alert.builder()
                .id(ids.get())
                .ruleId(m.ruleId()).ruleVersion(m.ruleVersion()).title(m.ruleName())
                .severity(m.severity()).priority(Priority.fromSeverity(m.severity()))
                .status(AlertStatus.DETECTED).evidenceLevel(m.evidenceLevel())
                .network(m.network()).groupKey(m.groupKey()).dedupKey(m.dedupKey())
                .addEventIds(m.matchedEventIds()).evidence(m.evidence())
                .firstSeen(m.eventTime()).lastSeen(m.eventTime()).occurrenceCount(1)
                .createdAt(now).updatedAt(now)
                .originChain(m.originChain()).hop(m.hop()).subject(m.subject())
                .addHistory(new HistoryEntry(null, AlertStatus.DETECTED, SYSTEM, now,
                        "rule " + m.ruleId() + "@" + m.ruleVersion() + " matched event " + m.triggeringEventId()))
                .build();
        return new MatchOutcome(Outcome.CREATED, repository.save(created));
    }

    /** @throws IllegalAlertTransitionException if the lifecycle does not allow it */
    public synchronized Alert transition(String alertId, AlertStatus to, String actor, String reason) {
        Objects.requireNonNull(to, "to");
        String who = actor(actor);
        Alert a = get(alertId);
        if (!a.status().canTransitionTo(to)) throw new IllegalAlertTransitionException(alertId, a.status(), to);
        Instant now = clock.instant();
        return repository.save(a.toBuilder().status(to).updatedAt(now)
                .addHistory(new HistoryEntry(a.status(), to, who, now, text(reason))).build());
    }

    public synchronized Alert assign(String alertId, String assignee, String actor) {
        Alert a = get(alertId);
        Instant now = clock.instant();
        return repository.save(a.toBuilder().assignee(assignee).updatedAt(now)
                .addHistory(new HistoryEntry(a.status(), a.status(), actor(actor), now, "assigned to " + assignee)).build());
    }

    public synchronized Alert comment(String alertId, String author, String text) {
        Alert a = get(alertId);
        Instant now = clock.instant();
        return repository.save(a.toBuilder().updatedAt(now).addComment(new Comment(actor(author), now, text(text))).build());
    }

    /** Raises the priority of overdue unacknowledged alerts; returns the escalated alerts. */
    public synchronized List<Alert> escalateOverdue() {
        Instant now = clock.instant();
        List<Alert> out = new ArrayList<>();
        for (Alert a : repository.findAll()) {
            if (!a.status().unacknowledged() || a.escalationLevel() >= escalation.maxEscalations()) continue;
            Instant since = a.lastEscalatedAt() != null ? a.lastEscalatedAt() : a.createdAt();
            if (Duration.between(since, now).compareTo(escalation.ackTimeout()) < 0) continue;
            Priority raised = a.priority().raise();
            out.add(repository.save(a.toBuilder().priority(raised).escalationLevel(a.escalationLevel() + 1)
                    .lastEscalatedAt(now).updatedAt(now)
                    .addHistory(new HistoryEntry(a.status(), a.status(), SYSTEM + ":escalation", now,
                            "not acknowledged after " + escalation.ackTimeout() + ": priority " + a.priority() + " -> " + raised))
                    .build()));
        }
        return out;
    }

    /** Annotates alerts whose evidence includes an event removed by a chain reorganisation. */
    public synchronized List<Alert> onRetraction(String eventId) {
        Instant now = clock.instant();
        List<Alert> out = new ArrayList<>();
        for (Alert a : repository.findByMatchedEventId(eventId)) {
            out.add(repository.save(a.toBuilder().updatedAt(now)
                    .addComment(new Comment(SYSTEM + ":reorg", now, "event " + eventId
                            + " was removed by a chain reorganisation; re-check the evidence")).build()));
        }
        return out;
    }

    public Optional<Alert> find(String alertId) { return repository.findById(alertId); }

    public AlertRepository repository() { return repository; }

    private Alert get(String id) {
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("unknown alert " + id));
    }

    private static boolean withinCooldown(Alert a, Instant t, Duration cooldown) {
        if (cooldown == null || cooldown.isZero()) return false;
        return Duration.between(a.lastSeen(), t).abs().compareTo(cooldown) <= 0;
    }

    private static String actor(String a) {
        if (a == null || a.isBlank()) throw new IllegalArgumentException("actor is required");
        return a.length() > 200 ? a.substring(0, 200) : a;
    }

    private static String text(String t) { return t == null ? null : t.length() > MAX_TEXT ? t.substring(0, MAX_TEXT) : t; }

    private static Instant min(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static Instant max(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
}
