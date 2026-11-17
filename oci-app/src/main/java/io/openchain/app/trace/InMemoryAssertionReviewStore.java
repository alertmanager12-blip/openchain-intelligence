package io.openchain.app.trace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Reviews in memory (lost on restart); versions come from the trace artifact store. */
public final class InMemoryAssertionReviewStore implements AssertionReviewStore {
    private final TraceArtifactStore artifacts;
    private final List<AssertionReview> rows = new ArrayList<>();

    public InMemoryAssertionReviewStore(TraceArtifactStore artifacts) { this.artifacts = artifacts; }

    @Override public synchronized void insert(AssertionReview r) {
        if (rows.stream().anyMatch(x -> x.id().equals(r.id()))) throw new IllegalStateException("duplicate review id");
        if (r.comment() != null && r.comment().length() > AssertionReview.MAX_COMMENT) throw new IllegalArgumentException("comment too long");
        if (artifacts.find(r.clientScope(), r.investigationId()).isEmpty()) throw new IllegalArgumentException("unknown investigation");
        rows.add(r);
    }

    @Override public synchronized List<AssertionReview> forLineage(String scope, String lineageId) {
        List<AssertionReview> out = new ArrayList<>();
        for (AssertionReview r : rows) {
            if (!r.clientScope().equals(scope)) continue;
            artifacts.find(scope, r.investigationId()).filter(a -> a.lineageId().equals(lineageId)).ifPresent(a -> out.add(
                    new AssertionReview(r.id(), r.clientScope(), r.investigationId(), r.assertionId(), r.decision(), r.comment(), r.actor(),
                            r.createdAt(), a.version())));
        }
        out.sort(Comparator.comparing(AssertionReview::createdAt).thenComparing(AssertionReview::id));
        return out;
    }
}
