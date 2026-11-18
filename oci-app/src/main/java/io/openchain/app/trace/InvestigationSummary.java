package io.openchain.app.trace;

import io.openchain.intelligence.investigation.SummaryCheck;
import java.time.Instant;
import java.util.List;

/**
 * One AI summary request of an investigation (ADR-0022 amendment 5), append-only. Only an ACCEPTED summary has
 * {@code sentences} to show; {@code draft} is the parsed answer, kept for audit and never shown.
 */
public record InvestigationSummary(String id, String clientScope, String investigationId, String provider, String model, String promptVersion,
                                   String inputHash, Status status, List<SummaryCheck.Sentence> sentences, List<SummaryCheck.Sentence> draft,
                                   List<String> reasons, String createdBy, Instant createdAt) {
    public enum Status { ACCEPTED, REFUSED, FAILED }
}
