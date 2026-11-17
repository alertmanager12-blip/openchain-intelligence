package io.openchain.app.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The in-memory review store behaves as the JDBC one (AssertionReviewStoreTest) on scope, lineage, versions and limits. */
class InMemoryAssertionReviewStoreTest {
    static final Instant T0 = Instant.parse("2026-11-17T10:00:00Z");
    final InMemoryTraceArtifactStore artifacts = new InMemoryTraceArtifactStore();
    final InMemoryAssertionReviewStore reviews = new InMemoryAssertionReviewStore(artifacts);

    void investigation(String scope, String id, String lineage, int version) {
        artifacts.insert(new TraceArtifact(id, scope, lineage, version, null, "ethereum", "0xa11ce", "BOTH", "plan@1", TraceArtifact.Status.DONE,
                "PARTIAL", false, null, "{}", "[]", "{}", null, null, "creator", T0, T0, List.of(), TraceArtifact.Kind.INVESTIGATION));
    }

    AssertionReview review(String scope, String inv, String as, String comment, int second) {
        return new AssertionReview("rv" + second, scope, inv, as, AssertionReview.Decision.ACCEPTED, comment, "analyst", T0.plusSeconds(second), 0);
    }

    @Test void scopeLineageVersionsAndLimits() {
        investigation("default", "inv1", "lin", 1);
        investigation("default", "inv2", "lin", 2);
        investigation("client-b", "invb", "lin", 1);
        reviews.insert(review("default", "inv2", "as_2", null, 2));
        reviews.insert(review("default", "inv1", "as_1", "ok", 1));
        reviews.insert(review("client-b", "invb", "as_1", null, 3));
        assertThat(reviews.forLineage("default", "lin")).extracting(r -> r.assertionId() + ":v" + r.investigationVersion())
                .containsExactly("as_1:v1", "as_2:v2");
        assertThatThrownBy(() -> reviews.insert(review("default", "invb", "as_1", null, 4))).as("another scope's investigation")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reviews.insert(review("default", "inv1", "as_1", "x".repeat(2001), 5))).isInstanceOf(IllegalArgumentException.class);
    }
}
