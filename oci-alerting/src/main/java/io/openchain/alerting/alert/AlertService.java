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

    /** @param before evidence state of the alert before this match (null for a created alert) */
    public record MatchOutcome(Outcome outcome, Alert alert, Alert.EvidenceState before) {
        public MatchOutcome(Outcome outcome, Alert alert) { this(outcome, alert, null); }
        public boolean created() { return outcome == Outcome.CREATED; }
        /**
         * The alert had been announced as withdrawn (alert.retracted) and has valid evidence again: re-included events or
         * a new match. The channels are told (alert.reinstated). Code review I4.
         */
        public boolean reinstated() {
            return before == Alert.EvidenceState.WITHDRAWN && alert.evidenceState() != Alert.EvidenceState.WITHDRAWN;
        }
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
                Alert r = reinstate(replay.get(), m.matchedEventIds(), now);
                // alerts stored before subjects existed get theirs from the replayed (re-indexed) trigger
                if ((r.subject() == null || r.subject().eventId() == null) && m.subject() != null && m.triggeringEventId().equals(r.matchedEventIds().isEmpty() ? null
                        : r.matchedEventIds().getLast())) {
                    r = repository.save(r.toBuilder().subject(m.subject()).build());
                }
                return new MatchOutcome(Outcome.GROUPED, r, replay.get().evidenceState());
            }
        }
        Optional<Alert> open = repository.findOpenByDedupKey(m.ruleId(), m.dedupKey());
        if (open.isPresent() && withinCooldown(open.get(), m.eventTime(), cooldown)) {
            Alert a = reinstate(open.get(), m.matchedEventIds(), now);
            Alert.Builder b = a.toBuilder()
                    .occurrenceCount(a.occurrenceCount() + 1)
                    .addEventIds(m.matchedEventIds())
                    .firstSeen(min(a.firstSeen(), m.eventTime()))
                    .lastSeen(max(a.lastSeen(), m.eventTime()))
                    .subject(m.subject() != null && !m.eventTime().isBefore(a.lastSeen()) ? m.subject() : a.subject())
                    .updatedAt(now);
            if (a.evidenceState() == Alert.EvidenceState.WITHDRAWN) {
                // a new, valid match grouped into an alert whose evidence had all been withdrawn: it is evidence again
                Alert probe = b.build();
                Alert.EvidenceState state = evidenceState(probe.matchedEventIds(), probe.withdrawnEventIds());
                if (state != Alert.EvidenceState.WITHDRAWN) {
                    evidenceReinstated.incrementAndGet();
                    b = probe.toBuilder().evidenceState(state).addHistory(new HistoryEntry(a.status(), a.status(), SYSTEM, now,
                            "new match " + m.triggeringEventId() + " of rule " + m.ruleId() + ": the alert has valid evidence again"));
                }
            }
            return new MatchOutcome(Outcome.GROUPED, repository.save(b.build()), open.get().evidenceState());
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
    /** How a retraction changed the evidence of one alert. */
    public record EvidenceChange(Alert alert, Alert.EvidenceState before, Alert.EvidenceState after) {
        /** The last valid piece of evidence just went: the channels that received the alert are told (alert.retracted). */
        public boolean becameWithdrawn() { return after == Alert.EvidenceState.WITHDRAWN && before != Alert.EvidenceState.WITHDRAWN; }
    }

    private final java.util.concurrent.atomic.AtomicLong evidencePartiallyWithdrawn = new java.util.concurrent.atomic.AtomicLong(),
            evidenceWithdrawn = new java.util.concurrent.atomic.AtomicLong(), evidenceReinstated = new java.util.concurrent.atomic.AtomicLong();

    public long evidencePartiallyWithdrawn() { return evidencePartiallyWithdrawn.get(); }
    public long evidenceWithdrawn() { return evidenceWithdrawn.get(); }
    public long evidenceReinstated() { return evidenceReinstated.get(); }

    private final java.util.concurrent.atomic.AtomicLong thresholdStillMet = new java.util.concurrent.atomic.AtomicLong(),
            thresholdNoLongerMet = new java.util.concurrent.atomic.AtomicLong(), thresholdUnverifiable = new java.util.concurrent.atomic.AtomicLong();

    /** Aggregation alerts re-evaluated after a retraction, by verdict (code review I3). */
    public long thresholdStillMet() { return thresholdStillMet.get(); }
    public long thresholdNoLongerMet() { return thresholdNoLongerMet.get(); }
    public long thresholdUnverifiable() { return thresholdUnverifiable.get(); }

    /**
     * An event matched by alerts left the canonical chain (reorganisation; the rule engine reports each retraction once).
     * The alert is never deleted: the event joins its withdrawn evidence and the evidence state is recomputed (some evidence
     * left: PARTIALLY_WITHDRAWN; none: WITHDRAWN), with a history entry. Idempotent: an event already withdrawn changes
     * nothing. See docs/P0_REORG_DESIGN.md § 3.3.
     */
    public synchronized List<EvidenceChange> onRetraction(String eventId) { return onRetraction(eventId, a -> Optional.empty()); }

    /**
     * Same, with the threshold of aggregation alerts re-evaluated (code review I3): the remaining events may no longer
     * reach the threshold, or still reach it although cited events were withdrawn. {@code recheck} answers for an alert
     * (empty: not an aggregation alert, or no engine state).
     * <ul>
     *   <li>STILL_MET: evidence state VALID, the withdrawn event is still recorded, the new value is in the history;</li>
     *   <li>NO_LONGER_MET: WITHDRAWN even if cited events remain, with the remaining value and the threshold;</li>
     *   <li>UNVERIFIABLE: the event-count rule as before, and the history says the threshold could not be re-verified.</li>
     * </ul>
     */
    public synchronized List<EvidenceChange> onRetraction(String eventId,
                                                          java.util.function.Function<Alert, Optional<io.openchain.alerting.engine.ThresholdCheck>> recheck) {
        Instant now = clock.instant();
        List<EvidenceChange> out = new ArrayList<>();
        for (Alert a : repository.findByMatchedEventId(eventId)) {
            if (a.withdrawnEventIds().contains(eventId)) continue;
            List<String> withdrawn = new ArrayList<>(a.withdrawnEventIds());
            withdrawn.add(eventId);
            Alert.EvidenceState state = evidenceState(a.matchedEventIds(), withdrawn);
            long remaining = a.matchedEventIds().stream().filter(id -> !withdrawn.contains(id)).count();
            String reason = "event " + eventId + " left the canonical chain (reorganisation): "
                    + (remaining == 0 ? "no valid evidence remains, re-check the alert before acting"
                    : remaining + " of " + a.matchedEventIds().size() + " matched event(s) remain");
            Optional<io.openchain.alerting.engine.ThresholdCheck> check = "AGGREGATION".equals(a.evidence().get("kind"))
                    ? recheck.apply(a) : Optional.empty();
            if (check.isPresent()) {
                var c = check.get();
                String measured = c.function() + " " + (c.value() == null ? "n/a" : c.value().stripTrailingZeros().toPlainString())
                        + " over " + c.eventCount() + " event(s), threshold " + c.operator() + " " + c.threshold().stripTrailingZeros().toPlainString();
                switch (c.verdict()) {
                    case STILL_MET -> {
                        state = Alert.EvidenceState.VALID;
                        reason = "event " + eventId + " left the canonical chain (reorganisation); threshold re-evaluated on the "
                                + "remaining events and still met: " + measured;
                        thresholdStillMet.incrementAndGet();
                    }
                    case NO_LONGER_MET -> {
                        state = Alert.EvidenceState.WITHDRAWN;
                        reason = "event " + eventId + " left the canonical chain (reorganisation); threshold re-evaluated on the "
                                + "remaining events and no longer met: " + measured + ". Re-check the alert before acting";
                        thresholdNoLongerMet.incrementAndGet();
                    }
                    case UNVERIFIABLE -> {
                        reason += "; threshold not re-verifiable (rule changed, engine restarted or window state discarded)";
                        thresholdUnverifiable.incrementAndGet();
                    }
                }
            }
            Alert saved = repository.save(a.toBuilder().updatedAt(now).withdrawnEventIds(withdrawn).evidenceState(state)
                    .addHistory(new HistoryEntry(a.status(), a.status(), SYSTEM + ":reorg", now, reason))
                    .addComment(new Comment(SYSTEM + ":reorg", now, reason)).build());
            if (state == Alert.EvidenceState.WITHDRAWN) evidenceWithdrawn.incrementAndGet(); else evidencePartiallyWithdrawn.incrementAndGet();
            out.add(new EvidenceChange(saved, a.evidenceState(), state));
        }
        return out;
    }

    /** VALID when nothing was withdrawn, WITHDRAWN when every matched event was, PARTIALLY_WITHDRAWN otherwise. */
    static Alert.EvidenceState evidenceState(List<String> matched, List<String> withdrawn) {
        if (withdrawn.isEmpty()) return Alert.EvidenceState.VALID;
        return matched.stream().allMatch(withdrawn::contains) ? Alert.EvidenceState.WITHDRAWN : Alert.EvidenceState.PARTIALLY_WITHDRAWN;
    }

    /** Events of this match that had been withdrawn are back on the canonical chain (re-inclusion): their evidence counts again. */
    private Alert reinstate(Alert a, java.util.Collection<String> eventIds, Instant now) {
        List<String> withdrawn = new ArrayList<>(a.withdrawnEventIds());
        if (!withdrawn.removeAll(eventIds)) return a;
        Alert.EvidenceState state = evidenceState(a.matchedEventIds(), withdrawn);
        evidenceReinstated.incrementAndGet();
        return repository.save(a.toBuilder().updatedAt(now).withdrawnEventIds(withdrawn).evidenceState(state)
                .addHistory(new HistoryEntry(a.status(), a.status(), SYSTEM + ":reorg", now,
                        "evidence re-included in the canonical chain: " + String.join(", ", eventIds.stream().filter(a.withdrawnEventIds()::contains).toList())))
                .build());
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
