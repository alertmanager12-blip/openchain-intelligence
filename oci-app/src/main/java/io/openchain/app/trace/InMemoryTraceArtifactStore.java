package io.openchain.app.trace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Trace artifacts in memory (lost on restart). */
public final class InMemoryTraceArtifactStore implements TraceArtifactStore {
    private final Map<String, TraceArtifact> byId = new LinkedHashMap<>();

    @Override public synchronized void insert(TraceArtifact a) {
        if (byId.putIfAbsent(a.id(), a) != null) throw new IllegalStateException("duplicate trace id");
    }

    @Override public synchronized void update(TraceArtifact a) {
        TraceArtifact old = byId.get(a.id());
        if (old == null || !old.clientScope().equals(a.clientScope())) throw new IllegalArgumentException("unknown trace");
        byId.put(a.id(), a);
    }

    @Override public synchronized Optional<TraceArtifact> find(String scope, String id) {
        return Optional.ofNullable(byId.get(id)).filter(a -> a.clientScope().equals(scope));
    }

    @Override public synchronized List<TraceArtifact> list(String scope, int limit) {
        return byId.values().stream().filter(a -> a.clientScope().equals(scope))
                .sorted(Comparator.comparing(TraceArtifact::createdAt).reversed().thenComparing(TraceArtifact::id)).limit(limit).toList();
    }

    @Override public synchronized int latestVersion(String scope, String lineageId) {
        return byId.values().stream().filter(a -> a.clientScope().equals(scope) && a.lineageId().equals(lineageId))
                .mapToInt(TraceArtifact::version).max().orElse(0);
    }

    @Override public synchronized List<TraceArtifact> unfinished() {
        return byId.values().stream().filter(a -> a.status() == TraceArtifact.Status.PENDING || a.status() == TraceArtifact.Status.RUNNING)
                .sorted(Comparator.comparing(TraceArtifact::createdAt).thenComparing(TraceArtifact::id)).toList();
    }

    @Override public synchronized int countActive(String scope) {
        return (int) unfinished().stream().filter(a -> a.clientScope().equals(scope)).count();
    }

    @Override public synchronized int countSince(String scope, java.time.Instant since) {
        return (int) byId.values().stream().filter(a -> a.clientScope().equals(scope) && !a.createdAt().isBefore(since)).count();
    }

    @Override public synchronized List<String> markStale(Set<String> eventIds) {
        List<String> hit = new ArrayList<>();
        for (TraceArtifact a : List.copyOf(byId.values())) {
            TreeSet<String> ids = new TreeSet<>(a.eventIds());
            ids.retainAll(eventIds);
            if (ids.isEmpty()) continue;
            TreeSet<String> all = new TreeSet<>(a.staleEventIds() == null ? List.of() : a.staleEventIds());
            all.addAll(ids);
            byId.put(a.id(), new TraceArtifact(a.id(), a.clientScope(), a.lineageId(), a.version(), a.previousId(), a.network(), a.seed(),
                    a.direction(), a.engineVersion(), a.status(), a.resultStatus(), true, List.copyOf(all), a.requestJson(), a.resultJson(),
                    a.evidenceJson(), a.diffJson(), a.error(), a.createdBy(), a.createdAt(), a.completedAt(), a.eventIds()));
            hit.add(a.id());
        }
        return hit;
    }
}
