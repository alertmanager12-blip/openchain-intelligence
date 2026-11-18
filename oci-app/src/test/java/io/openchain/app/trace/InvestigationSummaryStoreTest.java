package io.openchain.app.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.openchain.app.persistence.PostgresSupport;
import io.openchain.app.persistence.TransactionRunner;
import io.openchain.intelligence.investigation.SummaryCheck;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** ADR-0022 amendment 5 against PostgreSQL 17: summaries are append-only, scoped, deleted with their investigation. */
@PostgresSupport.RequiresDocker
class InvestigationSummaryStoreTest {
    static final Instant T0 = Instant.parse("2026-11-17T10:00:00Z");

    DataSource ds;
    NamedParameterJdbcTemplate jdbc;
    JdbcTraceArtifactStore artifacts;
    JdbcInvestigationSummaryStore store;

    @BeforeEach void database() {
        ds = PostgresSupport.newDatabase().dataSource();
        PostgresSupport.migrate(ds);
        jdbc = new NamedParameterJdbcTemplate(ds);
        TransactionRunner tx = new TransactionRunner() {
            final org.springframework.transaction.support.TransactionTemplate t =
                    new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
            @Override public <T> T inTransaction(java.util.function.Supplier<T> work) { return t.execute(s -> work.get()); }
        };
        artifacts = new JdbcTraceArtifactStore(jdbc, tx);
        store = new JdbcInvestigationSummaryStore(jdbc, tx);
        jdbc.update("insert into client_scopes (id, name) values ('client-b', 'B')", Map.of());
        artifacts.insert(new TraceArtifact("inv1", "default", "lin", 1, null, "ethereum", "0xa11ce", "BOTH", "plan@1", TraceArtifact.Status.DONE,
                "PARTIAL", false, null, "{}", "[]", "{}", null, null, "creator", T0, T0, List.of(), TraceArtifact.Kind.INVESTIGATION));
    }

    InvestigationSummary summary(String id, InvestigationSummary.Status st, int second) {
        List<SummaryCheck.Sentence> s = List.of(new SummaryCheck.Sentence("It received 1000 USDT.", List.of("as_exp")));
        return new InvestigationSummary(id, "default", "inv1", "claude", "claude-opus-5-5", "summary@1", "sha256:x", st,
                st == InvestigationSummary.Status.ACCEPTED ? s : null, s, List.of(), "analyst", T0.plusSeconds(second));
    }

    @Test void summariesRoundTripInOrderAndAreCountedPerScope() {
        store.insert(summary("sm2", InvestigationSummary.Status.REFUSED, 2));
        store.insert(summary("sm1", InvestigationSummary.Status.ACCEPTED, 1));
        List<InvestigationSummary> all = store.forInvestigation("default", "inv1");
        assertThat(all).extracting(InvestigationSummary::id).containsExactly("sm1", "sm2");
        assertThat(all.getFirst().sentences()).singleElement().satisfies(s -> assertThat(s.assertionIds()).containsExactly("as_exp"));
        assertThat(all.get(1).sentences()).isNull();
        assertThat(store.countSince("default", T0)).isEqualTo(2);
        assertThat(store.countSince("default", T0.plusSeconds(2))).isEqualTo(1);
        assertThat(store.forInvestigation("client-b", "inv1")).isEmpty();
        assertThat(store.countSince("client-b", T0)).isZero();
    }

    @Test void aSummaryIsNeverUpdatedNorDeletedDirectlyButGoesWithItsInvestigation() {
        store.insert(summary("sm1", InvestigationSummary.Status.ACCEPTED, 1));
        assertThatThrownBy(() -> jdbc.update("update investigation_summaries set status = 'REFUSED'", Map.of())).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from investigation_summaries", Map.of())).hasMessageContaining("append-only");
        jdbc.update("delete from trace_artifacts where id = 'inv1'", Map.of());
        assertThat(jdbc.queryForObject("select count(*) from investigation_summaries", Map.of(), Integer.class)).isZero();
    }
}
