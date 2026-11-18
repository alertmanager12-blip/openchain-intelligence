package io.openchain.app.trace;

import java.time.Instant;
import java.util.List;

/** AI summaries (V17), append-only and limited to one client scope. */
public interface InvestigationSummaryStore {
    void insert(InvestigationSummary s);

    /** Summaries of one investigation, oldest first. */
    List<InvestigationSummary> forInvestigation(String clientScope, String investigationId);

    /** Requests made by a client scope since an instant (daily cap). */
    int countSince(String clientScope, Instant since);
}
