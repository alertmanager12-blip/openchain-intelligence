package io.openchain.alerting.notify.delivery;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Outbox persistence port. A JDBC implementation must make {@link #findDue} + update safe for concurrent workers. */
public interface DeliveryStore {
    DeliveryRecord save(DeliveryRecord r);
    Optional<DeliveryRecord> find(String id);
    List<DeliveryRecord> findDue(Instant now, int limit);
    List<DeliveryRecord> findByStatus(DeliveryStatus status);
    List<DeliveryRecord> findByAlert(String alertId);
}
