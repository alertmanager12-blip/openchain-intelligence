package io.openchain.app.trace;

import io.openchain.app.security.ApiKeyService;
import io.openchain.intelligence.investigation.InvestigationPlan;
import io.openchain.intelligence.trace.FrozenSource;
import io.openchain.intelligence.trace.Trace;
import io.openchain.intelligence.trace.TraceDiff;
import io.openchain.intelligence.trace.TraceEngine;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Runs the trace engine and stores each run as an artifact with its frozen evidence (ADR-0022 amendment 1).
 * <ul>
 * <li>{@link #submit} stores a PENDING artifact and runs it on {@code executor} (a bounded queue in production); runs left
 *     PENDING or RUNNING by a restart are resumed by {@link #resumePending}.</li>
 * <li>A re-run is a new version of the same lineage with its {@link TraceDiff}; a stored version is never recomputed.</li>
 * <li>{@link #replay} runs the engine again on the frozen evidence and must give the stored result.</li>
 * <li>Budgets per client scope: at most {@link Budget#maxActive} runs pending or running, {@link Budget#maxPerDay} per day.</li>
 * </ul>
 */
public final class TraceArtifactService {

    public record Budget(int maxActive, int maxPerDay) {
        public static final Budget DEFAULTS = new Budget(3, 100);
    }

    /** Refused because a budget of the client scope is used up (HTTP 429). */
    public static final class BudgetExceeded extends RuntimeException {
        public BudgetExceeded(String m) { super(m); }
    }

    private final TraceArtifactStore store;
    private final Supplier<Trace.Source> liveSource;
    private final Clock clock;
    private final Executor executor;
    private final Budget budget;
    /** Inputs of the investigation plan for one address (coverage and signals as of now); null: investigations disabled. */
    private volatile java.util.function.Function<io.openchain.core.model.Address, InvestigationPlan.Inputs> investigationInputs;

    public void setInvestigationInputs(java.util.function.Function<io.openchain.core.model.Address, InvestigationPlan.Inputs> f) {
        this.investigationInputs = f;
    }

    public TraceArtifactService(TraceArtifactStore store, Supplier<Trace.Source> liveSource, Clock clock) {
        this(store, liveSource, clock, Runnable::run, Budget.DEFAULTS);
    }

    public TraceArtifactService(TraceArtifactStore store, Supplier<Trace.Source> liveSource, Clock clock, Executor executor, Budget budget) {
        this.store = store;
        this.liveSource = liveSource;
        this.clock = clock;
        this.executor = executor;
        this.budget = budget;
    }

    /** Runs {@code req} now (synchronously) as version 1 of a new lineage. */
    public TraceArtifact run(String scope, Trace.Request req, String actor) {
        TraceArtifact p = pending(scope, req, actor, UUID.randomUUID().toString(), 1, null);
        store.insert(p);
        return execute(p);
    }

    /** Runs the request of {@code previousId} again on the current data (synchronously): a new version, with its diff. */
    public Optional<TraceArtifact> rerun(String scope, String previousId, String actor) {
        return store.find(scope, previousId).map(prev -> {
            TraceArtifact p = pending(scope, TraceJson.request(prev.requestJson()), actor, prev.lineageId(),
                    store.latestVersion(scope, prev.lineageId()) + 1, prev);
            store.insert(p);
            return execute(p);
        });
    }

    /** Stores a PENDING run and queues it; the caller polls {@code find}. */
    public synchronized TraceArtifact submit(String scope, Trace.Request req, String actor) {
        checkBudget(scope);
        TraceArtifact p = pending(scope, req, actor, UUID.randomUUID().toString(), 1, null);
        store.insert(p);
        ApiKeyService.AUDIT.info("trace.create id={} network={} seed={} direction={} clientScope={} actor={}", p.id(), p.network(), p.seed(),
                p.direction(), scope, actor);
        queue(p);
        return p;
    }

    /** Queues a new version of {@code previousId}; empty when it is not an artifact of {@code scope}. */
    public synchronized Optional<TraceArtifact> submitRerun(String scope, String previousId, String actor) {
        Optional<TraceArtifact> prev = store.find(scope, previousId).filter(x -> x.kind() == TraceArtifact.Kind.TRACE);
        if (prev.isEmpty()) return Optional.empty();
        checkBudget(scope);
        TraceArtifact p = pending(scope, TraceJson.request(prev.get().requestJson()), actor, prev.get().lineageId(),
                store.latestVersion(scope, prev.get().lineageId()) + 1, prev.get());
        store.insert(p);
        ApiKeyService.AUDIT.info("trace.rerun id={} previous={} clientScope={} actor={}", p.id(), previousId, scope, actor);
        queue(p);
        return Optional.of(p);
    }

    /** Queues a stored PENDING run; a full queue fails it and refuses the request (429). */
    private void queue(TraceArtifact p) {
        try {
            executor.execute(() -> execute(p));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            store.update(with(p, TraceArtifact.Status.FAILED, null, null, null, null, "trace queue full", List.of()));
            throw new BudgetExceeded("the trace queue is full; try again later");
        }
    }

    // ---- investigations (ADR-0022 amendment 2) ------------------------------------------------------------------

    public static final String INVESTIGATION_ENGINE = InvestigationPlan.VERSION + "+" + Trace.ENGINE_VERSION;

    /** Stores a PENDING investigation of {@code subject} (its plan inputs taken now) and queues it. */
    public synchronized TraceArtifact submitInvestigation(String scope, io.openchain.core.model.Address subject, String actor) {
        checkBudget(scope);
        TraceArtifact p = pendingInvestigation(scope, subject, actor, UUID.randomUUID().toString(), 1, null);
        store.insert(p);
        ApiKeyService.AUDIT.info("investigation.create id={} network={} subject={} clientScope={} actor={}", p.id(), p.network(), p.seed(), scope, actor);
        queue(p);
        return p;
    }

    /** A new version of an investigation on the current data (new inputs), with the assertions added and removed. */
    public synchronized Optional<TraceArtifact> submitInvestigationRerun(String scope, String previousId, String actor) {
        Optional<TraceArtifact> prev = store.find(scope, previousId).filter(a -> a.kind() == TraceArtifact.Kind.INVESTIGATION);
        if (prev.isEmpty()) return Optional.empty();
        checkBudget(scope);
        InvestigationPlan.Inputs old = TraceJson.inputs(prev.get().requestJson());
        TraceArtifact p = pendingInvestigation(scope, old.subject(), actor, prev.get().lineageId(),
                store.latestVersion(scope, prev.get().lineageId()) + 1, prev.get());
        store.insert(p);
        ApiKeyService.AUDIT.info("investigation.rerun id={} previous={} clientScope={} actor={}", p.id(), previousId, scope, actor);
        queue(p);
        return Optional.of(p);
    }

    private TraceArtifact pendingInvestigation(String scope, io.openchain.core.model.Address subject, String actor, String lineage, int version,
                                               TraceArtifact previous) {
        var f = investigationInputs;
        if (f == null) throw new IllegalStateException("investigations are not configured");
        InvestigationPlan.Inputs in = f.apply(subject);
        return new TraceArtifact(UUID.randomUUID().toString(), scope, lineage, version, previous == null ? null : previous.id(),
                subject.network().id(), subject.value(), "BOTH", INVESTIGATION_ENGINE, TraceArtifact.Status.PENDING, null, false, null,
                TraceJson.write(in), null, null, null, null, actor, clock.instant(), null, List.of(), TraceArtifact.Kind.INVESTIGATION);
    }

    /** The plan run again on the frozen evidence and stored inputs of an investigation. */
    public Optional<List<InvestigationPlan.Assertion>> replayInvestigation(String scope, String id) {
        return store.find(scope, id).filter(a -> a.kind() == TraceArtifact.Kind.INVESTIGATION && a.evidenceJson() != null)
                .map(a -> InvestigationPlan.run(TraceJson.inputs(a.requestJson()), new FrozenSource(TraceJson.evidence(a.evidenceJson()))));
    }

    private TraceArtifact executeInvestigation(TraceArtifact current) {
        InvestigationPlan.Inputs in = TraceJson.inputs(current.requestJson());
        var recorder = FrozenSource.recording(liveSource.get());
        List<InvestigationPlan.Assertion> assertions = InvestigationPlan.run(in, recorder);
        FrozenSource.Snapshot evidence = recorder.snapshot();
        String diff = null;
        if (current.previousId() != null) {
            diff = store.find(current.clientScope(), current.previousId()).filter(x -> x.resultJson() != null).map(x -> {
                java.util.Set<String> before = new java.util.TreeSet<>(TraceJson.assertions(x.resultJson()).stream().map(InvestigationPlan.Assertion::id).toList());
                java.util.Set<String> after = new java.util.TreeSet<>(assertions.stream().map(InvestigationPlan.Assertion::id).toList());
                java.util.Set<String> added = new java.util.TreeSet<>(after); added.removeAll(before);
                java.util.Set<String> removed = new java.util.TreeSet<>(before); removed.removeAll(after);
                return TraceJson.write(java.util.Map.of("added", added, "removed", removed));
            }).orElse(null);
        }
        String state = "COMPLETE".equals(in.coverage().state()) ? "COMPLETE" : "PARTIAL";
        return with(current, TraceArtifact.Status.DONE, state, TraceJson.write(assertions), TraceJson.write(evidence), diff, null,
                evidence.flows().stream().map(fl -> fl.eventId()).toList());
    }

    /** Cancels a PENDING run of {@code scope}; empty when unknown in that scope, unchanged when already started or done. */
    public synchronized Optional<TraceArtifact> cancel(String scope, String id, String actor) {
        return store.find(scope, id).map(a -> {
            if (a.status() != TraceArtifact.Status.PENDING) return a;
            TraceArtifact c = with(a, TraceArtifact.Status.FAILED, null, null, null, null, "cancelled by " + actor, List.of());
            store.update(c);
            ApiKeyService.AUDIT.info("trace.cancel id={} clientScope={} actor={}", id, scope, actor);
            return c;
        });
    }

    /** Runs again what a restart left PENDING or RUNNING (system work across scopes). */
    public int resumePending() {
        List<TraceArtifact> l = store.unfinished();
        for (TraceArtifact a : l) executor.execute(() -> execute(a));
        return l.size();
    }

    private void checkBudget(String scope) {
        if (store.countActive(scope) >= budget.maxActive()) {
            throw new BudgetExceeded("at most " + budget.maxActive() + " traces pending or running per client; wait for one to finish");
        }
        if (store.countSince(scope, clock.instant().minus(Duration.ofDays(1))) >= budget.maxPerDay()) {
            throw new BudgetExceeded("at most " + budget.maxPerDay() + " traces per day per client");
        }
    }

    private TraceArtifact pending(String scope, Trace.Request req, String actor, String lineage, int version, TraceArtifact previous) {
        return new TraceArtifact(UUID.randomUUID().toString(), scope, lineage, version, previous == null ? null : previous.id(),
                req.network().id(), req.seed().value(), req.direction().name(), Trace.ENGINE_VERSION, TraceArtifact.Status.PENDING, null,
                false, null, TraceJson.write(req), null, null, null, null, actor, clock.instant(), null, List.of());
    }

    private TraceArtifact execute(TraceArtifact p) {
        TraceArtifact current = store.find(p.clientScope(), p.id()).orElse(p);
        if (current.status() == TraceArtifact.Status.DONE || (current.status() == TraceArtifact.Status.FAILED)) return current; // cancelled or done
        try {
            store.update(with(current, TraceArtifact.Status.RUNNING, null, null, null, null, null, List.of()));
            if (current.kind() == TraceArtifact.Kind.INVESTIGATION) {
                TraceArtifact done = executeInvestigation(current);
                store.update(done);
                return done;
            }
            Trace.Request req = TraceJson.request(current.requestJson());
            var recorder = FrozenSource.recording(liveSource.get());
            Trace.Result result = TraceEngine.run(req, recorder);
            FrozenSource.Snapshot evidence = recorder.snapshot();
            String diff = null;
            if (current.previousId() != null) {
                diff = store.find(current.clientScope(), current.previousId()).filter(x -> x.resultJson() != null)
                        .map(x -> TraceJson.write(TraceDiff.compare(TraceJson.result(x.resultJson()), result))).orElse(null);
            }
            TraceArtifact done = with(current, TraceArtifact.Status.DONE, result.status().name(), TraceJson.write(result),
                    TraceJson.write(evidence), diff, null, evidence.flows().stream().map(f -> f.eventId()).toList());
            store.update(done);
            return done;
        } catch (RuntimeException e) {
            String err = e.getClass().getSimpleName() + ": " + e.getMessage();
            TraceArtifact failed = with(current, TraceArtifact.Status.FAILED, null, null, null, null, err.length() > 500 ? err.substring(0, 500) : err, List.of());
            store.update(failed);
            return failed;
        }
    }

    private TraceArtifact with(TraceArtifact a, TraceArtifact.Status status, String resultStatus, String result, String evidence, String diff,
                               String error, List<String> eventIds) {
        boolean finished = status == TraceArtifact.Status.DONE || status == TraceArtifact.Status.FAILED;
        return new TraceArtifact(a.id(), a.clientScope(), a.lineageId(), a.version(), a.previousId(), a.network(), a.seed(), a.direction(),
                a.engineVersion(), status, resultStatus, a.stale(), a.staleEventIds(), a.requestJson(), result, evidence, diff, error,
                a.createdBy(), a.createdAt(), finished ? clock.instant() : null, eventIds, a.kind());
    }

    /** The engine run again on the frozen evidence of a stored artifact. */
    public Optional<Trace.Result> replay(String scope, String id) {
        return store.find(scope, id).filter(a -> a.evidenceJson() != null)
                .map(a -> TraceEngine.run(TraceJson.request(a.requestJson()), new FrozenSource(TraceJson.evidence(a.evidenceJson()))));
    }

    public Optional<TraceArtifact> find(String scope, String id) { return store.find(scope, id); }

    public List<TraceArtifact> list(String scope, int limit) { return store.list(scope, limit); }

    public List<TraceArtifact> list(String scope, int limit, TraceArtifact.Kind kind) { return store.list(scope, limit, kind); }
}
