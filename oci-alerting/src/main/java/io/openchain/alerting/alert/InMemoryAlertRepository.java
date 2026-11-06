package io.openchain.alerting.alert;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe in-memory repository (dev, tests, dry runs). */
public final class InMemoryAlertRepository implements AlertRepository {

    private final Map<String, Alert> byId = new ConcurrentHashMap<>();
    private final Map<String, String> openByDedup = new ConcurrentHashMap<>();

    private static String key(String ruleId, String dedupKey) { return ruleId + "\u0000" + dedupKey; }

    @Override public synchronized Alert save(Alert alert) {
        byId.put(alert.id(), alert);
        String k = key(alert.ruleId(), alert.dedupKey());
        if (alert.status().terminal()) openByDedup.remove(k, alert.id());
        else openByDedup.put(k, alert.id());
        return alert;
    }

    @Override public Optional<Alert> findById(String id) { return Optional.ofNullable(byId.get(id)); }

    @Override public Optional<Alert> findOpenByDedupKey(String ruleId, String dedupKey) {
        String id = openByDedup.get(key(ruleId, dedupKey));
        return id == null ? Optional.empty() : findById(id).filter(a -> !a.status().terminal());
    }

    @Override public List<Alert> findByStatus(AlertStatus status) {
        return byId.values().stream().filter(a -> a.status() == status).sorted(Comparator.comparing(Alert::createdAt)).toList();
    }

    @Override public List<Alert> findByMatchedEventId(String eventId) {
        return byId.values().stream().filter(a -> a.matchedEventIds().contains(eventId)).toList();
    }

    @Override public List<Alert> findAll() {
        return byId.values().stream().sorted(Comparator.comparing(Alert::createdAt).thenComparing(Alert::id)).toList();
    }
}
