package io.openchain.app.watchlist;

import io.openchain.app.persistence.Sql;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** {@code watchlist} table (V7). */
public final class JdbcWatchlistRepository implements WatchlistRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcWatchlistRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public boolean insert(Entry e) {
        return jdbc.update("insert into watchlist (id, network, address, name, created_by, created_at) "
                + "values (:id, :network, :address, :name, :by, :at) on conflict (network, address) do nothing",
                new MapSqlParameterSource().addValue("id", e.id()).addValue("network", e.network()).addValue("address", e.address())
                        .addValue("name", e.name()).addValue("by", e.createdBy()).addValue("at", Sql.ts(e.createdAt()))) == 1;
    }

    @Override public boolean delete(String id) { return jdbc.update("delete from watchlist where id = :id", Map.of("id", id)) == 1; }

    @Override public List<Entry> findAll() {
        return jdbc.query("select id, network, address, name, created_by, created_at from watchlist order by created_at, id", Map.of(),
                (rs, n) -> new Entry(rs.getString("id"), rs.getString("network"), rs.getString("address"), rs.getString("name"),
                        rs.getString("created_by"), Sql.instant(rs, "created_at")));
    }
}
