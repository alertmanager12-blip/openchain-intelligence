package io.openchain.app.trace;

import java.util.List;

/** Reviews of investigation assertions (V16), append-only and limited to one client scope. */
public interface AssertionReviewStore {
    void insert(AssertionReview r);

    /** Reviews taken on every version of one investigation lineage, oldest first, each with the version it was taken on. */
    List<AssertionReview> forLineage(String clientScope, String lineageId);
}
