package io.openchain.app.security;

import io.openchain.app.persistence.Sql;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** {@code users} table (role assignments). */
public final class JdbcUserRepository implements UserRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcUserRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static MapSqlParameterSource params(UserDirectory.User u) {
        return new MapSqlParameterSource().addValue("id", u.id()).addValue("email", u.email()).addValue("name", u.name())
                .addValue("role", u.role()).addValue("lastLogin", Sql.ts(u.lastLoginAt()));
    }

    @Override public void save(UserDirectory.User u) {
        jdbc.update("insert into users (id, email, name, role, last_login_at) values (:id, :email, :name, :role, :lastLogin) "
                + "on conflict (id) do update set email = excluded.email, name = excluded.name, role = excluded.role, "
                + "last_login_at = excluded.last_login_at, updated_at = now()", params(u));
    }

    @Override public boolean insertIfAbsent(UserDirectory.User u) {
        return jdbc.update("insert into users (id, email, name, role, last_login_at) values (:id, :email, :name, :role, :lastLogin) "
                + "on conflict (id) do nothing", params(u)) == 1;
    }

    @Override public Optional<UserDirectory.User> findById(String id) {
        return jdbc.query("select id, email, name, role, last_login_at from users where id = :id", Map.of("id", id), MAPPER)
                .stream().findFirst();
    }

    @Override public List<UserDirectory.User> findAll() {
        return jdbc.query("select id, email, name, role, last_login_at from users order by id", Map.of(), MAPPER);
    }

    private static final RowMapper<UserDirectory.User> MAPPER = (rs, n) -> new UserDirectory.User(rs.getString("id"),
            rs.getString("email"), rs.getString("name"), rs.getString("role"), Sql.instant(rs, "last_login_at"));
}
