package io.openchain.app.trace;

import io.openchain.app.persistence.Sql;
import io.openchain.app.persistence.TransactionRunner;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code assertion_reviews} (V16). Every query filters by client scope and sets {@code oci.scope} for row-level security;
 * the table refuses updates and direct deletes itself (trigger). Reads use their own read-only JDBC path, as
 * {@link JdbcTraceArtifactStore}.
 */
public final class JdbcAssertionReviewStore implements AssertionReviewStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionRunner tx;

    public JdbcAssertionReviewStore(NamedParameterJdbcTemplate jdbc, TransactionRunner tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override public void insert(AssertionReview r) {
        tx.inTransaction(() -> {
            jdbc.queryForObject("select set_config('oci.scope', :s, true)", Map.of("s", r.clientScope()), String.class);
            jdbc.update("insert into assertion_reviews (id, client_scope_id, investigation_id, assertion_id, decision, comment, actor, created_at) "
                    + "values (:id, :scope, :inv, :as, :d, :c, :actor, :at)", new MapSqlParameterSource().addValue("id", r.id())
                    .addValue("scope", r.clientScope()).addValue("inv", r.investigationId()).addValue("as", r.assertionId())
                    .addValue("d", r.decision().name()).addValue("c", r.comment()).addValue("actor", r.actor()).addValue("at", Sql.ts(r.createdAt())));
            return null;
        });
    }

    @Override public List<AssertionReview> forLineage(String scope, String lineageId) {
        return ScopedRead.inScope(jdbc, scope, con -> {
            List<AssertionReview> out = new ArrayList<>();
            try (var ps = con.prepareStatement("select r.id, r.client_scope_id, r.investigation_id, r.assertion_id, r.decision, r.comment, "
                    + "r.actor, r.created_at, t.version from assertion_reviews r join trace_artifacts t on t.id = r.investigation_id "
                    + "where r.client_scope_id = ? and t.client_scope_id = ? and t.lineage_id = ? order by r.created_at, r.id")) {
                ps.setString(1, scope);
                ps.setString(2, scope);
                ps.setString(3, lineageId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new AssertionReview(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                AssertionReview.Decision.valueOf(rs.getString(5)), rs.getString(6), rs.getString(7),
                                rs.getTimestamp(8).toInstant(), rs.getInt(9)));
                    }
                }
            }
            return out;
        });
    }
}
