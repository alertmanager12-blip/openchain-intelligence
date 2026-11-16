package io.openchain.app.trace;

import io.openchain.intelligence.trace.FrozenSource;
import io.openchain.intelligence.trace.Trace;
import io.openchain.intelligence.trace.TraceDiff;
import io.openchain.intelligence.trace.TraceEngine;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs the trace engine and stores the run as an artifact with its frozen evidence (ADR-0022 amendment 1). A run on newer
 * data is a new version of the same lineage, with the {@link TraceDiff} against the previous version; a stored version is
 * never recomputed. {@link #replay} runs the engine again on the frozen evidence and must give the stored result.
 */
public final class TraceArtifactService {
    private final TraceArtifactStore store;
    private final java.util.function.Supplier<Trace.Source> liveSource;
    private final Clock clock;

    public TraceArtifactService(TraceArtifactStore store, java.util.function.Supplier<Trace.Source> liveSource, Clock clock) {
        this.store = store;
        this.liveSource = liveSource;
        this.clock = clock;
    }

    /** Runs {@code req} now on the live data and stores it as version 1 of a new lineage. */
    public TraceArtifact run(String scope, Trace.Request req, String actor) {
        return execute(scope, req, actor, UUID.randomUUID().toString(), 1, null);
    }

    /** Runs the request of {@code previousId} again on the current data: a new version, with its diff. */
    public Optional<TraceArtifact> rerun(String scope, String previousId, String actor) {
        return store.find(scope, previousId).map(p -> {
            int next = store.latestVersion(scope, p.lineageId()) + 1;
            return execute(scope, TraceJson.request(p.requestJson()), actor, p.lineageId(), next, p);
        });
    }

    private TraceArtifact execute(String scope, Trace.Request req, String actor, String lineage, int version, TraceArtifact previous) {
        var recorder = FrozenSource.recording(liveSource.get());
        Trace.Result result = TraceEngine.run(req, recorder);
        FrozenSource.Snapshot evidence = recorder.snapshot();
        String diff = null;
        if (previous != null && previous.resultJson() != null) diff = TraceJson.write(TraceDiff.compare(TraceJson.result(previous.resultJson()), result));
        List<String> ids = evidence.flows().stream().map(f -> f.eventId()).toList();
        var now = clock.instant();
        TraceArtifact a = new TraceArtifact(UUID.randomUUID().toString(), scope, lineage, version, previous == null ? null : previous.id(),
                req.network().id(), req.seed().value(), req.direction().name(), Trace.ENGINE_VERSION, TraceArtifact.Status.DONE,
                result.status().name(), false, null, TraceJson.write(req), TraceJson.write(result), TraceJson.write(evidence), diff, null,
                actor, now, now, ids);
        store.insert(a);
        return a;
    }

    /** The engine run again on the frozen evidence of a stored artifact. */
    public Optional<Trace.Result> replay(String scope, String id) {
        return store.find(scope, id).filter(a -> a.evidenceJson() != null)
                .map(a -> TraceEngine.run(TraceJson.request(a.requestJson()), new FrozenSource(TraceJson.evidence(a.evidenceJson()))));
    }
}
