package io.openchain.app.trace;

import io.openchain.app.persistence.Sql;
import io.openchain.app.persistence.TransactionRunner;
import io.openchain.intelligence.investigation.SummaryCheck;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@code investigation_summaries} (V17): scope set for row-level security on every query; reads on their own JDBC path. */
public final class JdbcInvestigationSummaryStore implements InvestigationSummaryStore {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionRunner tx;

    public JdbcInvestigationSummaryStore(NamedParameterJdbcTemplate jdbc, TransactionRunner tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override public void insert(InvestigationSummary s) {
        tx.inTransaction(() -> {
            jdbc.queryForObject("select set_config('oci.scope', :s, true)", Map.of("s", s.clientScope()), String.class);
            jdbc.update("insert into investigation_summaries (id, client_scope_id, investigation_id, provider, model, prompt_version, input_hash, "
                    + "status, sentences, draft, reasons, created_by, created_at) values (:id, :scope, :inv, :provider, :model, :pv, :hash, :status, "
                    + "cast(:sentences as jsonb), cast(:draft as jsonb), cast(:reasons as jsonb), :by, :at)", new MapSqlParameterSource()
                    .addValue("id", s.id()).addValue("scope", s.clientScope()).addValue("inv", s.investigationId()).addValue("provider", s.provider())
                    .addValue("model", s.model()).addValue("pv", s.promptVersion()).addValue("hash", s.inputHash()).addValue("status", s.status().name())
                    .addValue("sentences", s.sentences() == null ? null : JSON.writeValueAsString(s.sentences()))
                    .addValue("draft", s.draft() == null ? null : JSON.writeValueAsString(s.draft()))
                    .addValue("reasons", JSON.writeValueAsString(s.reasons())).addValue("by", s.createdBy()).addValue("at", Sql.ts(s.createdAt())));
            return null;
        });
    }

    private static final TypeReference<List<SummaryCheck.Sentence>> SENTENCES = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

    @Override public List<InvestigationSummary> forInvestigation(String scope, String investigationId) {
        return ScopedRead.inScope(jdbc, scope, con -> {
            List<InvestigationSummary> out = new ArrayList<>();
            try (var ps = con.prepareStatement("select id, client_scope_id, investigation_id, provider, model, prompt_version, input_hash, status, "
                    + "sentences::text, draft::text, reasons::text, created_by, created_at from investigation_summaries "
                    + "where client_scope_id = ? and investigation_id = ? order by created_at, id")) {
                ps.setString(1, scope);
                ps.setString(2, investigationId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String sent = rs.getString(9), draft = rs.getString(10);
                        out.add(new InvestigationSummary(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                                rs.getString(6), rs.getString(7), InvestigationSummary.Status.valueOf(rs.getString(8)),
                                sent == null ? null : JSON.readValue(sent, SENTENCES), draft == null ? null : JSON.readValue(draft, SENTENCES),
                                JSON.readValue(rs.getString(11), STRINGS), rs.getString(12), rs.getTimestamp(13).toInstant()));
                    }
                }
            }
            return out;
        });
    }

    @Override public int countSince(String scope, Instant since) {
        return ScopedRead.inScope(jdbc, scope, con -> {
            try (var ps = con.prepareStatement("select count(*) from investigation_summaries where client_scope_id = ? and created_at >= ?")) {
                ps.setString(1, scope);
                ps.setTimestamp(2, java.sql.Timestamp.from(since));
                try (var rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
            }
        });
    }
}
