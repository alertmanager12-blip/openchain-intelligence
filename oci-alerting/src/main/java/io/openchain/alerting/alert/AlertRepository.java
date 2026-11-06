package io.openchain.alerting.alert;

import java.util.List;
import java.util.Optional;

/** Persistence port for alerts. oci-app may provide a JPA/JDBC implementation; {@link InMemoryAlertRepository} is the default. */
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
