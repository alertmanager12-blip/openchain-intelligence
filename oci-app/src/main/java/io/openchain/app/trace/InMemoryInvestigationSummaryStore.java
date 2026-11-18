package io.openchain.app.trace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** AI summaries in memory (lost on restart). */
public final class InMemoryInvestigationSummaryStore implements InvestigationSummaryStore {
    private final List<InvestigationSummary> rows = new ArrayList<>();

    @Override public synchronized void insert(InvestigationSummary s) {
        if (rows.stream().anyMatch(x -> x.id().equals(s.id()))) throw new IllegalStateException("duplicate summary id");
        rows.add(s);
    }

    @Override public synchronized List<InvestigationSummary> forInvestigation(String scope, String investigationId) {
        return rows.stream().filter(s -> s.clientScope().equals(scope) && s.investigationId().equals(investigationId))
                .sorted(Comparator.comparing(InvestigationSummary::createdAt).thenComparing(InvestigationSummary::id)).toList();
    }

    @Override public synchronized int countSince(String scope, Instant since) {
        return (int) rows.stream().filter(s -> s.clientScope().equals(scope) && !s.createdAt().isBefore(since)).count();
    }
}
