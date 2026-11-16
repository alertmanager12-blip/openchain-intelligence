package io.openchain.app.persistence;

import io.openchain.app.chain.IndexingCoverage;
import io.openchain.core.model.Network;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** {@code indexer_gaps} and {@code indexer_live_coverage} (V13). */
public final class JdbcIndexingCoverageStore implements IndexingCoverage.Store {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcIndexingCoverageStore(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void addGap(IndexingCoverage.Gap g) {
        jdbc.update("insert into indexer_gaps (network, from_block, to_block, cause, detected_at) values (:n, :f, :t, :c, :at) "
                + "on conflict (network, from_block) do nothing", new MapSqlParameterSource().addValue("n", g.network().id())
                .addValue("f", g.fromBlock()).addValue("t", g.toBlock()).addValue("c", g.cause().name()).addValue("at", Sql.ts(g.detectedAt())));
    }

    @Override public List<IndexingCoverage.Gap> gaps(Network n) {
        return jdbc.query("select from_block, to_block, cause, detected_at from indexer_gaps where network = :n order by from_block",
                Map.of("n", n.id()), (rs, i) -> new IndexingCoverage.Gap(n, rs.getLong("from_block"), rs.getLong("to_block"),
                        IndexingCoverage.Cause.valueOf(rs.getString("cause")), Sql.instant(rs, "detected_at")));
    }

    @Override public Optional<IndexingCoverage.LiveStart> liveStart(Network n) {
        return jdbc.query("select from_block, exact, since from indexer_live_coverage where network = :n", Map.of("n", n.id()),
                (rs, i) -> new IndexingCoverage.LiveStart(n, rs.getLong("from_block"), rs.getBoolean("exact"), Sql.instant(rs, "since")))
                .stream().findFirst();
    }

    @Override public void setLiveStart(IndexingCoverage.LiveStart s) {
        jdbc.update("insert into indexer_live_coverage (network, from_block, exact, since) values (:n, :f, :e, :s) "
                + "on conflict (network) do update set from_block = excluded.from_block, exact = excluded.exact, since = excluded.since",
                new MapSqlParameterSource().addValue("n", s.network().id()).addValue("f", s.fromBlock()).addValue("e", s.exact())
                        .addValue("s", Sql.ts(s.since())));
    }
}
