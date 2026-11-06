package io.openchain.app.labels;

import io.openchain.app.persistence.Sql;
import io.openchain.app.persistence.TypedJson;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** {@code label_reports} table. */
public final class JdbcLabelReportRepository implements LabelReportRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcLabelReportRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void insert(LabelReportStore.Report r) {
        jdbc.update("insert into label_reports (id, network, address, category, description, evidence_urls, status, reporter, created_at) "
                + "values (:id, :network, :address, :category, :description, cast(:urls as jsonb), :status, :reporter, :createdAt)",
                new MapSqlParameterSource().addValue("id", r.id()).addValue("network", r.network()).addValue("address", r.address())
                        .addValue("category", r.category()).addValue("description", r.description())
                        .addValue("urls", TypedJson.write(r.evidenceUrls())).addValue("status", r.status())
                        .addValue("reporter", r.reporter()).addValue("createdAt", Sql.ts(r.createdAt())));
    }

    @Override public List<LabelReportStore.Report> findAll() {
        return jdbc.query("select id, network, address, category, description, evidence_urls, status, reporter, created_at "
                + "from label_reports order by created_at desc, id desc", Map.of(), (rs, n) -> new LabelReportStore.Report(
                rs.getString("id"), rs.getString("network"), rs.getString("address"), rs.getString("category"),
                rs.getString("description"), TypedJson.readStrings(rs.getString("evidence_urls")), rs.getString("status"),
                rs.getString("reporter"), Sql.instant(rs, "created_at")));
    }
}
