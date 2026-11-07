package io.openchain.app.history;

import io.openchain.app.persistence.Sql;
import io.openchain.app.persistence.TransactionRunner;
import io.openchain.app.persistence.TypedJson;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.Network;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * PostgreSQL {@link AddressHistoryStore}: {@code address_history_progress} (one row per backfill) and
 * {@code address_history_events} (events found, fields as {@link TypedJson}). A chunk and its progress are written in
 * one transaction, so a crash never loses found events nor stores progress beyond them.
 */
public final class JdbcAddressHistoryStore implements AddressHistoryStore {

    private static final String COLUMNS = "id, network, address, source, status, from_block, to_block, covered_from_block, "
            + "covered_from_time, cursor, events_found, pages, truncated, last_error, created_at, updated_at";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionRunner tx;

    public JdbcAddressHistoryStore(NamedParameterJdbcTemplate jdbc, TransactionRunner tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override public Optional<Progress> load(String id) {
        return jdbc.query("select " + COLUMNS + " from address_history_progress where id = :id", Map.of("id", id),
                (rs, i) -> row(rs)).stream().findFirst();
    }

    @Override public List<Progress> all() {
        return jdbc.query("select " + COLUMNS + " from address_history_progress order by created_at, id", Map.of(), (rs, i) -> row(rs));
    }

    @Override public void save(Progress p) { tx.run(() -> upsert(p)); }

    @Override public void saveChunk(Progress p, List<ChainEvent> events) {
        tx.run(() -> {
            upsert(p);
            if (events.isEmpty()) return;
            SqlParameterSource[] rows = events.stream().map(e -> new MapSqlParameterSource()
                    .addValue("id", p.id()).addValue("eventId", e.eventId()).addValue("type", e.type().name())
                    .addValue("block", e.blockNumber()).addValue("blockHash", e.blockHash()).addValue("time", Sql.ts(e.eventTime()))
                    .addValue("fields", TypedJson.write(e.fields()))).toArray(SqlParameterSource[]::new);
            jdbc.batchUpdate("insert into address_history_events (history_id, event_id, event_type, block_number, block_hash, "
                    + "event_time, fields) values (:id, :eventId, :type, :block, :blockHash, :time, cast(:fields as jsonb)) "
                    + "on conflict (history_id, event_id) do nothing", rows);
        });
    }

    @Override public List<ChainEvent> events(String id) {
        Network n = load(id).map(Progress::network).orElse(null);
        if (n == null) return List.of();
        return jdbc.query("select event_id, event_type, block_number, block_hash, event_time, fields from address_history_events "
                + "where history_id = :id order by event_time, event_id", Map.of("id", id), (rs, i) -> new ChainEvent(
                rs.getString("event_id"), ChainEvent.EventType.valueOf(rs.getString("event_type")), n, rs.getLong("block_number"),
                rs.getString("block_hash"), Sql.instant(rs, "event_time"), Sql.instant(rs, "event_time"), false,
                TypedJson.readMap(rs.getString("fields"))));
    }

    private void upsert(Progress p) {
        MapSqlParameterSource m = new MapSqlParameterSource()
                .addValue("id", p.id()).addValue("network", p.network().id()).addValue("address", p.address())
                .addValue("source", p.source()).addValue("status", p.status().name()).addValue("fromBlock", p.fromBlock())
                .addValue("toBlock", p.toBlock()).addValue("coveredFrom", p.coveredFromBlock())
                .addValue("coveredFromTime", Sql.ts(p.coveredFromTime())).addValue("cursor", p.cursor())
                .addValue("eventsFound", p.eventsFound()).addValue("pages", p.pages()).addValue("truncated", p.truncated())
                .addValue("lastError", p.lastError()).addValue("createdAt", Sql.ts(p.createdAt())).addValue("updatedAt", Sql.ts(p.updatedAt()));
        jdbc.update("insert into address_history_progress (" + COLUMNS + ") values (:id, :network, :address, :source, :status, "
                + ":fromBlock, :toBlock, :coveredFrom, :coveredFromTime, :cursor, :eventsFound, :pages, :truncated, :lastError, "
                + ":createdAt, :updatedAt) on conflict (id) do update set source = excluded.source, status = excluded.status, "
                + "from_block = excluded.from_block, to_block = excluded.to_block, covered_from_block = excluded.covered_from_block, "
                + "covered_from_time = excluded.covered_from_time, cursor = excluded.cursor, events_found = excluded.events_found, "
                + "pages = excluded.pages, truncated = excluded.truncated, last_error = excluded.last_error, updated_at = excluded.updated_at", m);
    }

    private static Progress row(ResultSet rs) throws SQLException {
        long cf = rs.getLong("covered_from_block");
        Long coveredFrom = rs.wasNull() ? null : cf;
        return new Progress(rs.getString("id"), Network.fromId(rs.getString("network")), rs.getString("address"),
                rs.getString("source"), Status.valueOf(rs.getString("status")), rs.getLong("from_block"), rs.getLong("to_block"),
                coveredFrom, Sql.instant(rs, "covered_from_time"), rs.getString("cursor"), rs.getLong("events_found"),
                rs.getInt("pages"), rs.getBoolean("truncated"), rs.getString("last_error"), Sql.instant(rs, "created_at"),
                Sql.instant(rs, "updated_at"));
    }
}
