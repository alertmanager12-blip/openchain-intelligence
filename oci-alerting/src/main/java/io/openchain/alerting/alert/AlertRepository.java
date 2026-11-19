package io.openchain.alerting.alert;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for alerts. oci-app provides a JDBC implementation (PostgreSQL); {@link InMemoryAlertRepository} is
 * the default. Implementations supporting optimistic locking check {@link Alert#version()} on save and throw
 * {@link ConcurrentAlertModificationException} when it is stale; {@link #save} returns the stored snapshot.
 */
public interface AlertRepository {

    Alert save(Alert alert);

    Optional<Alert> findById(String id);

    /** Most recent non-terminal alert for (rule, dedup key), used for grouping within cooldown. */
    Optional<Alert> findOpenByDedupKey(String ruleId, String dedupKey);

    List<Alert> findByStatus(AlertStatus status);

    /** Alerts whose evidence references the event (used on reorg retraction). */
    List<Alert> findByMatchedEventId(String eventId);

    List<Alert> findAll();

    /**
     * Filter of {@link #findPage}; a null component means "any". {@code subjectAddress} is compared without case to the
     * address stored with the alert ({@link AlertSubject#address()}).
     */
    record AlertQuery(java.util.Set<AlertStatus> statuses, io.openchain.core.detection.Severity severity, String ruleId,
                      io.openchain.core.model.Network network, java.time.Instant from, java.time.Instant to, String subjectAddress) {
        public static AlertQuery any() { return new AlertQuery(null, null, null, null, null, null, null); }

        public boolean matches(Alert a) {
            if (statuses != null && !statuses.contains(a.status())) return false;
            if (severity != null && a.severity() != severity) return false;
            if (ruleId != null && !ruleId.equals(a.ruleId())) return false;
            if (network != null && a.network() != network) return false;
            if (from != null && a.createdAt().isBefore(from)) return false;
            if (to != null && a.createdAt().isAfter(to)) return false;
            if (subjectAddress != null && (a.subject() == null || a.subject().address() == null
                    || !subjectAddress.equalsIgnoreCase(a.subject().address()))) return false;
            return true;
        }
    }

    /** Number of alerts per (status, severity): totals and breakdowns without loading the alerts. */
    record StatusSeverityCount(AlertStatus status, io.openchain.core.detection.Severity severity, long count) {}

    /**
     * One page of the alerts matching {@code q}, newest first (creation time, then id, descending). Implementations
     * backed by a database filter, sort and limit in the store: listing must not load every alert (code review I24).
     */
    default List<Alert> findPage(AlertQuery q, int offset, int limit) {
        return findAll().stream().filter(q::matches)
                .sorted(java.util.Comparator.comparing(Alert::createdAt).thenComparing(Alert::id).reversed())
                .skip(Math.max(0, offset)).limit(Math.max(0, limit)).toList();
    }

    default List<StatusSeverityCount> countByStatusAndSeverity() {
        java.util.Map<String, long[]> m = new java.util.TreeMap<>();
        java.util.Map<String, StatusSeverityCount> keys = new java.util.HashMap<>();
        for (Alert a : findAll()) {
            String k = a.status() + "|" + a.severity();
            m.computeIfAbsent(k, x -> new long[1])[0]++;
            keys.putIfAbsent(k, new StatusSeverityCount(a.status(), a.severity(), 0));
        }
        return m.entrySet().stream().map(e -> new StatusSeverityCount(keys.get(e.getKey()).status(), keys.get(e.getKey()).severity(),
                e.getValue()[0])).toList();
    }

    /** Creation times of the alerts created at or after {@code since} (activity charts). */
    default List<java.time.Instant> createdAtSince(java.time.Instant since) {
        return findAll().stream().map(Alert::createdAt).filter(c -> c != null && !c.isBefore(since)).toList();
    }

    /** Alert counts per rule (noise report); implementations may override with an aggregate query. */
    default List<RuleAlertStats> statsByRule(java.time.Instant since) {
        java.util.Map<String, long[]> m = new java.util.TreeMap<>();
        for (Alert a : findAll()) {
            long[] c = m.computeIfAbsent(a.ruleId(), k -> new long[5]);
            c[0]++;
            if (!a.status().terminal()) c[1]++;
            if (a.status() == AlertStatus.FALSE_POSITIVE) c[2]++;
            if (a.status() == AlertStatus.RESOLVED) c[3]++;
            if (since != null && a.createdAt() != null && !a.createdAt().isBefore(since)) c[4]++;
        }
        return m.entrySet().stream().map(e -> new RuleAlertStats(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2],
                e.getValue()[3], e.getValue()[4])).toList();
    }

    /** Per rule: alerts whose status is in the given "open" set, and alerts created at or after {@code since} (any status). */
    record RuleOpenAndRecent(String ruleId, long open, long createdSince) {}

    /**
     * The most severe alerts whose status is in {@code open}: severity CRITICAL first, then the newest (creation time, then
     * id, descending). The overview's "handle now" list (spec 2026-11-19); database implementations sort and limit in SQL.
     */
    default List<Alert> findTopOpen(java.util.Set<AlertStatus> open, int limit) {
        java.util.Comparator<Alert> order = java.util.Comparator.<Alert>comparingInt(a -> a.severity().ordinal()).reversed()
                .thenComparing(Alert::createdAt, java.util.Comparator.reverseOrder())
                .thenComparing(Alert::id, java.util.Comparator.reverseOrder());
        return findAll().stream().filter(a -> open.contains(a.status())).sorted(order).limit(Math.max(0, limit)).toList();
    }

    /** Open and recently created alerts per rule; rules with neither are left out. */
    default List<RuleOpenAndRecent> openAndRecentByRule(java.util.Set<AlertStatus> open, java.time.Instant since) {
        java.util.Map<String, long[]> m = new java.util.TreeMap<>();
        for (Alert a : findAll()) {
            boolean isOpen = open.contains(a.status());
            boolean recent = a.createdAt() != null && !a.createdAt().isBefore(since);
            if (!isOpen && !recent) continue;
            long[] c = m.computeIfAbsent(a.ruleId(), k -> new long[2]);
            if (isOpen) c[0]++;
            if (recent) c[1]++;
        }
        return m.entrySet().stream().map(e -> new RuleOpenAndRecent(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();
    }
}
