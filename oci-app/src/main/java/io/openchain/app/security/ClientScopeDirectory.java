package io.openchain.app.security;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The client scopes known to this instance (decision D1). {@link ClientScope#DEFAULT} always exists. A credential whose
 * scope is unknown or disabled does not authenticate.
 */
public final class ClientScopeDirectory {

    public static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");

    public record Entry(String id, String name, boolean active) {}

    /** Storage: the {@code client_scopes} table or memory. */
    public interface Store {
        Optional<Entry> find(String id);
        /** Inserts the scope, or reactivates and renames it when it exists. */
        void upsert(String id, String name);
        void setActive(String id, boolean active);
    }

    private final Store store;

    public ClientScopeDirectory(Store store) { this.store = store; }

    /** Memory only, with the default scope: in-memory mode and tests. */
    public static ClientScopeDirectory inMemory() {
        MemoryStore m = new MemoryStore();
        m.upsert(ClientScope.DEFAULT, "Default client");
        return new ClientScopeDirectory(m);
    }

    /** The {@code client_scopes} table; its {@code default} row is created by the V12 migration. Not queried here. */
    public static ClientScopeDirectory jdbc(NamedParameterJdbcTemplate jdbc) { return new ClientScopeDirectory(new JdbcStore(jdbc)); }

    public boolean isActive(String id) { return id != null && store.find(id).map(Entry::active).orElse(false); }

    public Optional<Entry> find(String id) { return id == null ? Optional.empty() : store.find(id); }

    public void register(String id, String name) {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("invalid client scope id");
        store.upsert(id, name == null || name.isBlank() ? id : name.strip());
        ApiKeyService.AUDIT.info("client_scope.register id={} name={}", id, name);
    }

    public void disable(String id) {
        if (ClientScope.DEFAULT.equals(id)) throw new IllegalArgumentException("the default client scope cannot be disabled");
        store.setActive(id, false);
        ApiKeyService.AUDIT.info("client_scope.disable id={}", id);
    }

    static final class MemoryStore implements Store {
        private final Map<String, Entry> entries = new ConcurrentHashMap<>();
        @Override public Optional<Entry> find(String id) { return Optional.ofNullable(entries.get(id)); }
        @Override public void upsert(String id, String name) { entries.put(id, new Entry(id, name, true)); }
        @Override public void setActive(String id, boolean active) { entries.computeIfPresent(id, (k, e) -> new Entry(k, e.name(), active)); }
    }

    static final class JdbcStore implements Store {
        private final NamedParameterJdbcTemplate jdbc;
        JdbcStore(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

        @Override public Optional<Entry> find(String id) {
            List<Entry> r = jdbc.query("select id, name, active from client_scopes where id = :id", Map.of("id", id),
                    (rs, n) -> new Entry(rs.getString("id"), rs.getString("name"), rs.getBoolean("active")));
            return r.stream().findFirst();
        }

        @Override public void upsert(String id, String name) {
            jdbc.update("insert into client_scopes (id, name) values (:id, :name) "
                    + "on conflict (id) do update set name = excluded.name, active = true", Map.of("id", id, "name", name));
        }

        @Override public void setActive(String id, boolean active) {
            jdbc.update("update client_scopes set active = :a where id = :id", Map.of("id", id, "a", active));
        }
    }
}
