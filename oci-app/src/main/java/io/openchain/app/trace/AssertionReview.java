package io.openchain.app.trace;

import java.time.Instant;

/**
 * A human decision on one assertion of one investigation (ADR-0022 amendment 3). It sits next to the assertion and never
 * changes it, its level or the report text. Append-only: a new decision is a new review.
 *
 * @param investigationVersion version of the investigation the review was taken on; filled when read, ignored on insert
 */
public record AssertionReview(String id, String clientScope, String investigationId, String assertionId, Decision decision, String comment,
                              String actor, Instant createdAt, int investigationVersion) {
    public enum Decision { ACCEPTED, REJECTED, NEEDS_EVIDENCE }

    public static final int MAX_COMMENT = 2_000;
}
