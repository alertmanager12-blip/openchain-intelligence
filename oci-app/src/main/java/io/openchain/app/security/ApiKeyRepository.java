package io.openchain.app.security;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Storage of API key metadata and hashes ({@link InMemoryApiKeyRepository}, {@link JdbcApiKeyRepository}). Never secrets. */
public interface ApiKeyRepository {

    /** @throws IllegalStateException when the prefix is already used */
    void insert(ApiKeyService.StoredKey key);

    Optional<ApiKeyService.StoredKey> findById(String id);

    Optional<ApiKeyService.StoredKey> findByPrefix(String prefix);

    List<ApiKeyService.StoredKey> findAll();

    /** Sets {@code revoked_at} if not already set. */
    void markRevoked(String id, Instant at);

    void touch(String id, Instant lastUsedAt);

    void updateScopes(String id, Set<String> scopes);

    void delete(String id);
}
