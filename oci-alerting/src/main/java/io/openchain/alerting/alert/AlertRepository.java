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
}
