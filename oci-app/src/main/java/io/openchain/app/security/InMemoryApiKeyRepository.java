package io.openchain.app.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Keys in memory (lost on restart). */
public final class InMemoryApiKeyRepository implements ApiKeyRepository {

    private final Map<String, ApiKeyService.StoredKey> byId = new ConcurrentHashMap<>();
    private final Map<String, String> idByPrefix = new ConcurrentHashMap<>();

    @Override public synchronized void insert(ApiKeyService.StoredKey k) {
        if (idByPrefix.putIfAbsent(k.prefix(), k.id()) != null) throw new IllegalStateException("duplicate API key prefix " + k.displayPrefix());
        byId.put(k.id(), k);
    }

    @Override public Optional<ApiKeyService.StoredKey> findById(String id) { return Optional.ofNullable(byId.get(id)); }

    @Override public Optional<ApiKeyService.StoredKey> findByPrefix(String prefix) {
        String id = idByPrefix.get(prefix);
        return id == null ? Optional.empty() : findById(id);
    }

    @Override public List<ApiKeyService.StoredKey> findAll() { return List.copyOf(byId.values()); }

    @Override public void markRevoked(String id, Instant at) { byId.computeIfPresent(id, (k, v) -> v.revoked() ? v : v.withRevokedAt(at)); }

    @Override public void touch(String id, Instant at) { byId.computeIfPresent(id, (k, v) -> v.withLastUsedAt(at)); }

    @Override public void updateScopes(String id, Set<String> scopes) { byId.computeIfPresent(id, (k, v) -> v.withScopes(scopes)); }

    @Override public synchronized void delete(String id) {
        ApiKeyService.StoredKey k = byId.remove(id);
        if (k != null) idByPrefix.remove(k.prefix(), id);
    }
}
