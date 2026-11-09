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
}
