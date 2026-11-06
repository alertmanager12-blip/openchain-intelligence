package io.openchain.alerting.notify.delivery;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryDeliveryStore implements DeliveryStore {
    private final Map<String, DeliveryRecord> rows = new ConcurrentHashMap<>();

    @Override public DeliveryRecord save(DeliveryRecord r) { rows.put(r.id(), r); return r; }
    @Override public Optional<DeliveryRecord> find(String id) { return Optional.ofNullable(rows.get(id)); }

    @Override public List<DeliveryRecord> findDue(Instant now, int limit) {
        return rows.values().stream()
                .filter(r -> r.status() == DeliveryStatus.PENDING && !r.nextAttemptAt().isAfter(now))
                .sorted(Comparator.comparing(DeliveryRecord::nextAttemptAt).thenComparing(DeliveryRecord::id))
                .limit(limit).toList();
    }

    @Override public List<DeliveryRecord> findByStatus(DeliveryStatus s) {
        return rows.values().stream().filter(r -> r.status() == s).sorted(Comparator.comparing(DeliveryRecord::createdAt)).toList();
    }

    @Override public List<DeliveryRecord> findByAlert(String alertId) {
        return rows.values().stream().filter(r -> r.alertId().equals(alertId)).sorted(Comparator.comparing(DeliveryRecord::createdAt)).toList();
    }
}
