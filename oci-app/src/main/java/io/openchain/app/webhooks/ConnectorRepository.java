package io.openchain.app.webhooks;

import java.time.Instant;
import java.util.List;

/**
 * Connector storage ({@link InMemoryConnectorRepository}, {@link JdbcConnectorRepository}). Implementations receive
 * plaintext targets and secrets; the JDBC one encrypts them at rest (AES-256-GCM) and never logs them.
 */
public interface ConnectorRepository {

    /** A stored connector with its decrypted secret ({@code null} for types without one). */
    record Stored(WebhookRegistry.Webhook webhook, String secret) {}

    void insert(WebhookRegistry.Webhook webhook, String secret, String actor);

    List<Stored> findAll();

    void delete(String id);

    void updateDelivery(String id, Instant at, String status);
}
