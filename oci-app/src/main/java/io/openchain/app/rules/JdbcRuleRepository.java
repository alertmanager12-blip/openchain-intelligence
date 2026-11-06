package io.openchain.app.rules;

import io.openchain.alerting.rule.RuleParser.Format;
import io.openchain.app.persistence.OptimisticLockException;
import io.openchain.app.persistence.Sql;
import io.openchain.app.persistence.TransactionRunner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code rules} (current version pointer, enabled, tombstone, {@code row_version}) + immutable {@code rule_versions}
 * (source text as submitted, compiled hash). Rule row and new versions are written in one transaction.
 */
public final class JdbcRuleRepository implements RuleRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionRunner tx;

    public JdbcRuleRepository(NamedParameterJdbcTemplate jdbc, TransactionRunner tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override public List<RuleRow> findAll() { return load(null); }

    @Override public Optional<RuleRow> find(String id) { return load(id).stream().findFirst(); }

    private List<RuleRow> load(String id) {
        String where = id == null ? "" : " where rule_id = :id";
        Map<String, Object> p = id == null ? Map.of() : Map.of("id", id);
        Map<String, List<VersionRow>> versions = new HashMap<>();
        jdbc.query("select rule_id, version, source, format, compiled_hash, rule_semver, created_at from rule_versions" + where
                + " order by rule_id, version", p, rs -> {
            versions.computeIfAbsent(rs.getString("rule_id"), k -> new ArrayList<>()).add(new VersionRow(rs.getInt("version"),
                    rs.getString("source"), Format.valueOf(rs.getString("format")), rs.getString("compiled_hash"),
                    rs.getString("rule_semver"), Sql.instant(rs, "created_at")));
        });
        Map<String, RuleRow> out = new LinkedHashMap<>();
        jdbc.query("select id, origin, enabled, current_version, created_at, updated_at, deleted_at, row_version from rules"
                + (id == null ? "" : " where id = :id") + " order by id", p, rs -> {
            String rid = rs.getString("id");
            int current = rs.getInt("current_version");
            List<VersionRow> vs = versions.getOrDefault(rid, List.of()).stream().filter(v -> v.number() <= current).toList();
            if (vs.isEmpty()) return; // cannot happen with the FK + transaction; ignore a corrupt row rather than fail startup
            out.put(rid, new RuleRow(rid, rs.getString("origin"), rs.getBoolean("enabled"), vs, Sql.instant(rs, "created_at"),
                    Sql.instant(rs, "updated_at"), Sql.instant(rs, "deleted_at"), rs.getLong("row_version")));
        });
        return new ArrayList<>(out.values());
    }

    @Override
    public RuleRow save(RuleRow r) {
        return tx.inTransaction(() -> {
            long next = r.rowVersion() + 1;
            MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", r.id()).addValue("origin", r.origin())
                    .addValue("enabled", r.enabled()).addValue("current", r.current().number())
                    .addValue("createdAt", Sql.ts(r.createdAt())).addValue("updatedAt", Sql.ts(r.updatedAt()))
                    .addValue("deletedAt", Sql.ts(r.deletedAt())).addValue("expected", r.rowVersion()).addValue("next", next);
            int rows = r.rowVersion() == 0
                    ? jdbc.update("insert into rules (id, origin, enabled, current_version, created_at, updated_at, deleted_at, row_version) "
                            + "values (:id, :origin, :enabled, :current, :createdAt, :updatedAt, :deletedAt, :next) on conflict (id) do nothing", p)
                    : jdbc.update("update rules set origin = :origin, enabled = :enabled, current_version = :current, "
                            + "updated_at = :updatedAt, deleted_at = :deletedAt, row_version = :next where id = :id and row_version = :expected", p);
            if (rows != 1) throw new OptimisticLockException("rule '" + r.id() + "' was modified concurrently; reload and retry");
            Integer max = jdbc.queryForObject("select max(version) from rule_versions where rule_id = :id", Map.of("id", r.id()), Integer.class);
            int stored = max == null ? 0 : max;
            for (VersionRow v : r.versions()) {
                if (v.number() <= stored) continue; // immutable
                jdbc.update("insert into rule_versions (rule_id, version, source, format, compiled_hash, rule_semver, created_at) "
                        + "values (:id, :v, :source, :format, :hash, :semver, :createdAt)", new MapSqlParameterSource()
                        .addValue("id", r.id()).addValue("v", v.number()).addValue("source", v.source())
                        .addValue("format", v.format().name()).addValue("hash", v.compiledHash()).addValue("semver", v.ruleVersion())
                        .addValue("createdAt", Sql.ts(v.createdAt())));
            }
            return new RuleRow(r.id(), r.origin(), r.enabled(), r.versions(), r.createdAt(), r.updatedAt(), r.deletedAt(), next);
        });
    }
}
