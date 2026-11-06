package io.openchain.app.webhooks;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In memory: lost on restart (nothing to reload). */
public final class InMemoryConnectorRepository implements ConnectorRepository {
    private final Map<String, Stored> rows = new ConcurrentHashMap<>();

    @Override public void insert(WebhookRegistry.Webhook w, String secret, String actor) { rows.put(w.id(), new Stored(w, secret)); }
    @Override public List<Stored> findAll() { return List.copyOf(rows.values()); }
    @Override public void delete(String id) { rows.remove(id); }
    @Override public void updateDelivery(String id, Instant at, String status) {
        rows.computeIfPresent(id, (k, s) -> new Stored(s.webhook().withDelivery(at, "OK".equals(status)), s.secret()));
    }
}
