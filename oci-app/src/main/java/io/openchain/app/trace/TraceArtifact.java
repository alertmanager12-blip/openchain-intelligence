package io.openchain.app.trace;

import java.time.Instant;
import java.util.List;

/**
 * One stored run of the trace engine (ADR-0022 amendment 1). JSON columns hold the request, the result, the frozen
 * evidence and the diff with the previous version, as written by {@link TraceJson}.
 */
public record TraceArtifact(String id, String clientScope, String lineageId, int version, String previousId, String network,
                            String seed, String direction, String engineVersion, Status status, String resultStatus, boolean stale,
                            List<String> staleEventIds, String requestJson, String resultJson, String evidenceJson, String diffJson,
                            String error, String createdBy, Instant createdAt, Instant completedAt, List<String> eventIds) {

    public enum Status { PENDING, RUNNING, DONE, FAILED }
}
