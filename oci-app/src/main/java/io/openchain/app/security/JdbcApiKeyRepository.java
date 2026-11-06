package io.openchain.app.security;

import io.openchain.app.persistence.Sql;
import io.openchain.app.persistence.TypedJson;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** {@code api_keys} table: prefix + HMAC hash, scopes, expiry, revocation, last use. Never the secret. */
public final class JdbcApiKeyRepository implements ApiKeyRepository {

    private static final String COLUMNS = "id, name, env, prefix, secret_hash, scopes, created_by, created_at, expires_at, last_used_at, revoked_at";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcApiKeyRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void insert(ApiKeyService.StoredKey k) {
        try {
            jdbc.update("insert into api_keys (" + COLUMNS + ") values (:id, :name, :env, :prefix, :hash, cast(:scopes as jsonb), "
                    + ":createdBy, :createdAt, :expiresAt, :lastUsedAt, :revokedAt)", new MapSqlParameterSource()
                    .addValue("id", k.id()).addValue("name", k.name()).addValue("env", k.env()).addValue("prefix", k.prefix())
                    .addValue("hash", k.hash()).addValue("scopes", TypedJson.write(k.scopes().stream().sorted().toList()))
                    .addValue("createdBy", k.createdBy()).addValue("createdAt", Sql.ts(k.createdAt()))
                    .addValue("expiresAt", Sql.ts(k.expiresAt())).addValue("lastUsedAt", Sql.ts(k.lastUsedAt()))
                    .addValue("revokedAt", Sql.ts(k.revokedAt())));
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("duplicate API key prefix " + k.displayPrefix());
        }
    }

    @Override public Optional<ApiKeyService.StoredKey> findById(String id) {
        return jdbc.query("select " + COLUMNS + " from api_keys where id = :id", Map.of("id", id), MAPPER).stream().findFirst();
    }

    @Override public Optional<ApiKeyService.StoredKey> findByPrefix(String prefix) {
        return jdbc.query("select " + COLUMNS + " from api_keys where prefix = :p", Map.of("p", prefix), MAPPER).stream().findFirst();
    }

    @Override public List<ApiKeyService.StoredKey> findAll() {
        return jdbc.query("select " + COLUMNS + " from api_keys order by created_at desc", Map.of(), MAPPER);
    }

    @Override public void markRevoked(String id, Instant at) {
        jdbc.update("update api_keys set revoked_at = :at where id = :id and revoked_at is null", Map.of("id", id, "at", Sql.ts(at)));
    }

    @Override public void touch(String id, Instant at) {
        jdbc.update("update api_keys set last_used_at = :at where id = :id and (last_used_at is null or last_used_at < :at)",
                Map.of("id", id, "at", Sql.ts(at)));
    }

    @Override public void updateScopes(String id, Set<String> scopes) {
        jdbc.update("update api_keys set scopes = cast(:s as jsonb) where id = :id",
                Map.of("id", id, "s", TypedJson.write(scopes.stream().sorted().toList())));
    }

    @Override public void delete(String id) { jdbc.update("delete from api_keys where id = :id", Map.of("id", id)); }

    private static final RowMapper<ApiKeyService.StoredKey> MAPPER = (rs, n) -> new ApiKeyService.StoredKey(rs.getString("id"),
            rs.getString("name"), rs.getString("env"), rs.getString("prefix"), rs.getBytes("secret_hash"),
            new LinkedHashSet<>(TypedJson.readStrings(rs.getString("scopes"))), Sql.instant(rs, "created_at"),
            Sql.instant(rs, "expires_at"), rs.getString("created_by"), Sql.instant(rs, "last_used_at"), Sql.instant(rs, "revoked_at"));
}
