package io.openchain.app.trace;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Trace artifacts. Every read and write of one client's artifacts takes its client scope (decision D1): there is no
 * lookup by id alone. {@link #markStale} is system work across scopes (a reorganisation retracts a public flow).
 */
public interface TraceArtifactStore {
    void insert(TraceArtifact a);

    /** Replaces a stored artifact of {@code a.clientScope()} (status, result, evidence; never its identity). */
    void update(TraceArtifact a);

    Optional<TraceArtifact> find(String clientScope, String id);

    /** Newest first, of one kind. */
    List<TraceArtifact> list(String clientScope, int limit, TraceArtifact.Kind kind);

    /** Trace artifacts, newest first. */
    default List<TraceArtifact> list(String clientScope, int limit) { return list(clientScope, limit, TraceArtifact.Kind.TRACE); }

    int latestVersion(String clientScope, String lineageId);

    /** Runs left PENDING or RUNNING (by a restart), oldest first, across scopes (system work). */
    List<TraceArtifact> unfinished();

    /** Runs of {@code clientScope} PENDING or RUNNING. */
    int countActive(String clientScope);

    /** Runs of {@code clientScope} created at or after {@code since}. */
    int countSince(String clientScope, java.time.Instant since);

    /** Marks STALE every artifact holding one of these event ids; returns their ids. */
    List<String> markStale(Set<String> eventIds);
}
