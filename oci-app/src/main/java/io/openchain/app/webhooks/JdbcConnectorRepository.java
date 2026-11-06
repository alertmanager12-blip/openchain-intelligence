package io.openchain.app.webhooks;

import io.openchain.app.persistence.PersistenceStartupException;
import io.openchain.app.persistence.SecretCipher;
import io.openchain.app.persistence.Sql;
import io.openchain.app.persistence.TypedJson;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code connectors} table. The target (webhook / Slack / Discord URL, Telegram chat id: often credential-bearing)
 * and the WEBHOOK signing secret are stored AES-256-GCM encrypted ({@link SecretCipher}: key id + nonce + ciphertext,
 * associated data = connector id + column); only a masked target is stored in clear for diagnostics.
 */
public final class JdbcConnectorRepository implements ConnectorRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final SecretCipher cipher;

    public JdbcConnectorRepository(NamedParameterJdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    static String aad(String id, String column) { return "connectors:" + id + ":" + column; }

    @Override
    public void insert(WebhookRegistry.Webhook w, String secret, String actor) {
        SecretCipher.Sealed target = cipher.seal(w.url(), aad(w.id(), "target"));
        SecretCipher.Sealed sec = secret == null ? null : cipher.seal(secret, aad(w.id(), "secret"));
        jdbc.update("insert into connectors (id, type, name, target_masked, events, enabled, created_at, created_by, enc_key_id, "
                + "target_nonce, target_ciphertext, secret_nonce, secret_ciphertext) values (:id, :type, :name, :masked, "
                + "cast(:events as jsonb), :enabled, :createdAt, :createdBy, :keyId, :tn, :tc, :sn, :sc)", new MapSqlParameterSource()
                .addValue("id", w.id()).addValue("type", w.type()).addValue("name", w.name())
                .addValue("masked", WebhookRegistry.masked(w)).addValue("events", TypedJson.write(w.events()))
                .addValue("enabled", w.enabled()).addValue("createdAt", Sql.ts(w.createdAt())).addValue("createdBy", actor)
                .addValue("keyId", target.keyId()).addValue("tn", target.nonce()).addValue("tc", target.ciphertext())
                .addValue("sn", sec == null ? null : sec.nonce()).addValue("sc", sec == null ? null : sec.ciphertext()));
    }

    /**
     * @throws PersistenceStartupException when a row cannot be decrypted with the configured master key (wrong or
     *         rotated OCI_CRYPTO_MASTER_KEY): failing loudly beats silently dropping connectors
     */
    @Override
    public List<Stored> findAll() {
        return jdbc.query("select id, type, name, events, enabled, created_at, last_delivery_at, last_delivery_status, enc_key_id, "
                + "target_nonce, target_ciphertext, secret_nonce, secret_ciphertext from connectors order by created_at, id", Map.of(),
                (rs, n) -> {
                    String id = rs.getString("id");
                    String keyId = rs.getString("enc_key_id");
                    String target, secret = null;
                    try {
                        target = cipher.open(new SecretCipher.Sealed(keyId, rs.getBytes("target_nonce"), rs.getBytes("target_ciphertext")),
                                aad(id, "target"));
                        byte[] sc = rs.getBytes("secret_ciphertext");
                        if (sc != null) secret = cipher.open(new SecretCipher.Sealed(keyId, rs.getBytes("secret_nonce"), sc), aad(id, "secret"));
                    } catch (SecretCipher.DecryptionException e) {
                        throw new PersistenceStartupException("cannot decrypt the secrets of connector " + id + ": " + e.getMessage(),
                                "Start with the OCI_CRYPTO_MASTER_KEY that encrypted the connectors, or delete and recreate them.", e);
                    }
                    WebhookRegistry.Webhook w = new WebhookRegistry.Webhook(id, rs.getString("type"), rs.getString("name"), target,
                            TypedJson.readStrings(rs.getString("events")), rs.getBoolean("enabled"), Sql.instant(rs, "created_at"),
                            Sql.instant(rs, "last_delivery_at"), rs.getString("last_delivery_status"));
                    return new Stored(w, secret);
                });
    }

    @Override public void delete(String id) { jdbc.update("delete from connectors where id = :id", Map.of("id", id)); }

    @Override public void updateDelivery(String id, Instant at, String status) {
        jdbc.update("update connectors set last_delivery_at = :at, last_delivery_status = :s, updated_at = now() where id = :id",
                Map.of("id", id, "at", Sql.ts(at), "s", status));
    }
}
