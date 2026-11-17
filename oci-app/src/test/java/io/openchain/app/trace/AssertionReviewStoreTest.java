package io.openchain.app.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.openchain.app.persistence.PostgresSupport;
import io.openchain.app.persistence.TransactionRunner;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** ADR-0022 amendment 3, step 1, against PostgreSQL 17: reviews are append-only, scoped and follow the lineage. */
@PostgresSupport.RequiresDocker
class AssertionReviewStoreTest {
    static final Instant T0 = Instant.parse("2026-11-17T10:00:00Z");

    DataSource ds;
    NamedParameterJdbcTemplate jdbc;
    JdbcTraceArtifactStore artifacts;
    JdbcAssertionReviewStore reviews;

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
        reviews = new JdbcAssertionReviewStore(jdbc, tx);
        jdbc.update("insert into client_scopes (id, name) values ('client-b', 'B')", Map.of());
    }

    TraceArtifact investigation(String scope, String id, String lineage, int version) {
        TraceArtifact a = new TraceArtifact(id, scope, lineage, version, null, "ethereum", "0xa11ce", "BOTH", "plan@1",
                TraceArtifact.Status.DONE, "PARTIAL", false, null, "{}", "[]", "{}", null, null, "creator", T0, T0, List.of(),
                TraceArtifact.Kind.INVESTIGATION);
        artifacts.insert(a);
        return a;
    }

    AssertionReview review(String scope, String investigation, String assertion, AssertionReview.Decision d, int second) {
        return new AssertionReview("rv_" + investigation + "_" + assertion + "_" + second, scope, investigation, assertion, d,
                "checked " + second, "analyst", T0.plusSeconds(second), 0);
    }

    @Test void reviewsOfEveryVersionOfALineageAreReadInOrderWithTheirVersion() {
        investigation("default", "inv1", "lin", 1);
        investigation("default", "inv2", "lin", 2);
        investigation("default", "other", "lin-other", 1);
        reviews.insert(review("default", "inv1", "as_1", AssertionReview.Decision.NEEDS_EVIDENCE, 1));
        reviews.insert(review("default", "inv1", "as_1", AssertionReview.Decision.ACCEPTED, 2));
        reviews.insert(review("default", "inv2", "as_2", AssertionReview.Decision.REJECTED, 3));
        reviews.insert(review("default", "other", "as_1", AssertionReview.Decision.ACCEPTED, 4));

        assertThat(reviews.forLineage("default", "lin")).extracting(r -> r.assertionId() + ":" + r.decision() + ":v" + r.investigationVersion())
                .containsExactly("as_1:NEEDS_EVIDENCE:v1", "as_1:ACCEPTED:v1", "as_2:REJECTED:v2");
        assertThat(reviews.forLineage("default", "lin").getFirst().comment()).isEqualTo("checked 1");
    }

    @Test void anotherScopeSeesNoReview() {
        investigation("default", "inv1", "lin", 1);
        reviews.insert(review("default", "inv1", "as_1", AssertionReview.Decision.ACCEPTED, 1));
        assertThat(reviews.forLineage("client-b", "lin")).isEmpty();
    }

    @Test void aReviewIsNeverUpdatedNorDeletedDirectlyEvenByTheOwner() {
        investigation("default", "inv1", "lin", 1);
        reviews.insert(review("default", "inv1", "as_1", AssertionReview.Decision.ACCEPTED, 1));
        assertThatThrownBy(() -> jdbc.update("update assertion_reviews set decision = 'REJECTED'", Map.of()))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from assertion_reviews", Map.of())).hasMessageContaining("append-only");
        assertThat(reviews.forLineage("default", "lin")).singleElement()
                .satisfies(r -> assertThat(r.decision()).isEqualTo(AssertionReview.Decision.ACCEPTED));
    }

    @Test void reviewsAreDeletedWithTheirInvestigation() {
        investigation("default", "inv1", "lin", 1);
        reviews.insert(review("default", "inv1", "as_1", AssertionReview.Decision.ACCEPTED, 1));
        jdbc.update("delete from trace_artifacts where id = 'inv1'", Map.of());
        assertThat(jdbc.queryForObject("select count(*) from assertion_reviews", Map.of(), Integer.class)).isZero();
    }

    @Test void aCommentLongerThan2000CharactersIsRefusedByTheDatabase() {
        investigation("default", "inv1", "lin", 1);
        AssertionReview r = review("default", "inv1", "as_1", AssertionReview.Decision.ACCEPTED, 1);
        AssertionReview tooLong = new AssertionReview(r.id(), r.clientScope(), r.investigationId(), r.assertionId(), r.decision(),
                "x".repeat(2001), r.actor(), r.createdAt(), 0);
        assertThatThrownBy(() -> reviews.insert(tooLong)).hasMessageContaining("assertion_reviews_comment_check");
    }

    /** As N10: a role that is not superuser sees no review unless oci.scope is set, then only its scope. */
    @Test void rowLevelSecurityHidesReviewsFromANonSuperuserRole() throws Exception {
        investigation("default", "inv1", "lin", 1);
        investigation("client-b", "invb", "lin-b", 1);
        reviews.insert(review("default", "inv1", "as_1", AssertionReview.Decision.ACCEPTED, 1));
        reviews.insert(review("client-b", "invb", "as_1", AssertionReview.Decision.REJECTED, 2));
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("create role oci_review_test nosuperuser nobypassrls");
            st.execute("grant select on assertion_reviews, trace_artifacts to oci_review_test");
            c.setAutoCommit(false);
            st.execute("set local role oci_review_test");
            assertThat(count(st)).isZero();
            st.execute("select set_config('oci.scope', 'client-b', true)");
            assertThat(count(st)).isEqualTo(1);
            c.rollback();
        }
    }

    static int count(Statement st) throws Exception {
        try (ResultSet rs = st.executeQuery("select count(*) from assertion_reviews")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
