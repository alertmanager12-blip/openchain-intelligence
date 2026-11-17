package io.openchain.app.trace;

import io.openchain.app.persistence.Sql;
import io.openchain.app.persistence.TransactionRunner;
import io.openchain.app.persistence.TypedJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code trace_artifacts} and {@code trace_artifact_flows} (V14). Every query filters by client scope, and every
 * transaction first sets {@code oci.scope} for the row-level security policies (effective when the application does not
 * connect as a superuser or a BYPASSRLS role).
 */
public final class JdbcTraceArtifactStore implements TraceArtifactStore {
    private static final String COLUMNS = "id, client_scope_id, lineage_id, version, previous_id, network, seed, direction, engine_version, "
            + "status, result_status, stale, stale_event_ids, request, result, evidence, diff, error, created_by, created_at, completed_at, kind";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionRunner tx;

    public JdbcTraceArtifactStore(NamedParameterJdbcTemplate jdbc, TransactionRunner tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    private void scope(String s) { jdbc.queryForObject("select set_config('oci.scope', :s, true)", Map.of("s", s), String.class); }

    private static MapSqlParameterSource params(TraceArtifact a) {
        return new MapSqlParameterSource().addValue("id", a.id()).addValue("scope", a.clientScope()).addValue("lineage", a.lineageId())
                .addValue("version", a.version()).addValue("previous", a.previousId()).addValue("network", a.network()).addValue("seed", a.seed())
                .addValue("direction", a.direction()).addValue("engine", a.engineVersion()).addValue("status", a.status().name())
                .addValue("resultStatus", a.resultStatus()).addValue("stale", a.stale())
                .addValue("staleIds", a.staleEventIds() == null ? null : TypedJson.write(a.staleEventIds()))
                .addValue("request", a.requestJson()).addValue("result", a.resultJson()).addValue("evidence", a.evidenceJson())
                .addValue("diff", a.diffJson()).addValue("error", a.error()).addValue("createdBy", a.createdBy())
                .addValue("createdAt", Sql.ts(a.createdAt())).addValue("completedAt", Sql.ts(a.completedAt())).addValue("kind", a.kind().name());
    }

    @Override public void insert(TraceArtifact a) {
        tx.inTransaction(() -> {
            scope(a.clientScope());
            jdbc.update("insert into trace_artifacts (" + COLUMNS + ") values (:id, :scope, :lineage, :version, :previous, :network, :seed, "
                    + ":direction, :engine, :status, :resultStatus, :stale, cast(:staleIds as jsonb), cast(:request as jsonb), cast(:result as jsonb), "
                    + "cast(:evidence as jsonb), cast(:diff as jsonb), :error, :createdBy, :createdAt, :completedAt, :kind)", params(a));
            flows(a);
            return null;
        });
    }

    @Override public void update(TraceArtifact a) {
        tx.inTransaction(() -> {
            scope(a.clientScope());
            int n = jdbc.update("update trace_artifacts set status = :status, result_status = :resultStatus, stale = :stale, "
                    + "stale_event_ids = cast(:staleIds as jsonb), result = cast(:result as jsonb), evidence = cast(:evidence as jsonb), "
                    + "diff = cast(:diff as jsonb), error = :error, completed_at = :completedAt where id = :id and client_scope_id = :scope", params(a));
            if (n != 1) throw new IllegalArgumentException("unknown trace");
            jdbc.update("delete from trace_artifact_flows where artifact_id = :id and client_scope_id = :scope", params(a));
            flows(a);
            return null;
        });
    }

    private void flows(TraceArtifact a) {
        List<MapSqlParameterSource> rows = new ArrayList<>();
        for (String e : a.eventIds()) rows.add(new MapSqlParameterSource().addValue("id", a.id()).addValue("scope", a.clientScope()).addValue("e", e));
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("insert into trace_artifact_flows (artifact_id, client_scope_id, event_id) values (:id, :scope, :e) on conflict do nothing",
                    rows.toArray(MapSqlParameterSource[]::new));
        }
    }

    @Override public Optional<TraceArtifact> find(String s, String id) {
        return tx.inTransaction(() -> {
            scope(s);
            return jdbc.query("select " + COLUMNS + " from trace_artifacts where id = :id and client_scope_id = :s", Map.of("id", id, "s", s), MAPPER)
                    .stream().findFirst().map(a -> withIds(a));
        });
    }

    @Override public List<TraceArtifact> list(String s, int limit, TraceArtifact.Kind kind) {
        return tx.inTransaction(() -> {
            scope(s);
            return jdbc.query("select " + COLUMNS + " from trace_artifacts where client_scope_id = :s and kind = :k order by created_at desc, id limit :l",
                    Map.of("s", s, "l", limit, "k", kind.name()), MAPPER);
        });
    }

    @Override public int latestVersion(String s, String lineage) {
        return tx.inTransaction(() -> {
            scope(s);
            Integer v = jdbc.queryForObject("select max(version) from trace_artifacts where client_scope_id = :s and lineage_id = :l",
                    Map.of("s", s, "l", lineage), Integer.class);
            return v == null ? 0 : v;
        });
    }

    @Override public List<TraceArtifact> unfinished() {
        return tx.inTransaction(() -> {
            scope("*");
            return jdbc.query("select " + COLUMNS + " from trace_artifacts where status in ('PENDING', 'RUNNING') order by created_at, id",
                    Map.of(), MAPPER);
        });
    }

    @Override public int countActive(String s) {
        return tx.inTransaction(() -> {
            scope(s);
            Integer n = jdbc.queryForObject("select count(*) from trace_artifacts where client_scope_id = :s and status in ('PENDING', 'RUNNING')",
                    Map.of("s", s), Integer.class);
            return n == null ? 0 : n;
        });
    }

    @Override public int countSince(String s, java.time.Instant since) {
        return tx.inTransaction(() -> {
            scope(s);
            Integer n = jdbc.queryForObject("select count(*) from trace_artifacts where client_scope_id = :s and created_at >= :t",
                    Map.of("s", s, "t", Sql.ts(since)), Integer.class);
            return n == null ? 0 : n;
        });
    }

    @Override public List<String> markStale(Set<String> eventIds) {
        if (eventIds.isEmpty()) return List.of();
        return tx.inTransaction(() -> {
            scope("*"); // system work: a retracted flow is public data, the artifacts holding it may belong to any scope
            List<String> ids = jdbc.queryForList("select distinct artifact_id from trace_artifact_flows where event_id in (:e) order by artifact_id",
                    Map.of("e", eventIds), String.class);
            for (String id : ids) {
                List<String> hit = jdbc.queryForList("select event_id from trace_artifact_flows where artifact_id = :id and event_id in (:e)",
                        Map.of("id", id, "e", eventIds), String.class);
                String old = jdbc.queryForObject("select coalesce(stale_event_ids, '[]'::jsonb)::text from trace_artifacts where id = :id",
                        Map.of("id", id), String.class);
                java.util.TreeSet<String> all = new java.util.TreeSet<>(TypedJson.readStrings(old));
                all.addAll(hit);
                jdbc.update("update trace_artifacts set stale = true, stale_event_ids = cast(:s as jsonb) where id = :id",
                        Map.of("id", id, "s", TypedJson.write(List.copyOf(all))));
            }
            return ids;
        });
    }

    private TraceArtifact withIds(TraceArtifact a) {
        List<String> ids = jdbc.queryForList("select event_id from trace_artifact_flows where artifact_id = :id order by event_id",
                Map.of("id", a.id()), String.class);
        return new TraceArtifact(a.id(), a.clientScope(), a.lineageId(), a.version(), a.previousId(), a.network(), a.seed(), a.direction(),
                a.engineVersion(), a.status(), a.resultStatus(), a.stale(), a.staleEventIds(), a.requestJson(), a.resultJson(), a.evidenceJson(),
                a.diffJson(), a.error(), a.createdBy(), a.createdAt(), a.completedAt(), ids, a.kind());
    }

    private static final RowMapper<TraceArtifact> MAPPER = (rs, n) -> new TraceArtifact(rs.getString("id"), rs.getString("client_scope_id"),
            rs.getString("lineage_id"), rs.getInt("version"), rs.getString("previous_id"), rs.getString("network"), rs.getString("seed"),
            rs.getString("direction"), rs.getString("engine_version"), TraceArtifact.Status.valueOf(rs.getString("status")),
            rs.getString("result_status"), rs.getBoolean("stale"),
            rs.getString("stale_event_ids") == null ? null : TypedJson.readStrings(rs.getString("stale_event_ids")),
            rs.getString("request"), rs.getString("result"), rs.getString("evidence"), rs.getString("diff"), rs.getString("error"),
            rs.getString("created_by"), Sql.instant(rs, "created_at"), Sql.instant(rs, "completed_at"), List.of(),
            TraceArtifact.Kind.valueOf(rs.getString("kind")));
}
