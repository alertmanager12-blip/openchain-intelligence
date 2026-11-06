package io.openchain.alerting.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-key state of an ordered sequence rule (step 1 then step 2 ... within T, same key). Each step keeps the recent
 * events that matched it, sorted by event time, so out-of-order arrival (within lateness) still forms the chain.
 * Equal timestamps are accepted as ordered (events of the same block share a timestamp); distinct events are required.
 */
final class SequenceState {

    record Entry(String eventId, Instant time) {}

    private final List<List<Entry>> steps;

    SequenceState(int stepCount) {
        steps = new ArrayList<>(stepCount);
        for (int i = 0; i < stepCount; i++) steps.add(new ArrayList<>());
    }

    /** @return true if an old entry had to be dropped */
    boolean add(int step, Entry e, int maxPerStep) {
        List<Entry> l = steps.get(step);
        int idx = 0;
        while (idx < l.size() && !l.get(idx).time().isAfter(e.time())) idx++;
        l.add(idx, e);
        if (l.size() > maxPerStep) { l.removeFirst(); return true; }
        return false;
    }

    boolean remove(String eventId) {
        boolean any = false;
        for (List<Entry> l : steps) any |= l.removeIf(e -> e.eventId().equals(eventId));
        return any;
    }

    void evictUpTo(Instant limit) {
        for (List<Entry> l : steps) l.removeIf(e -> !e.time().isAfter(limit));
    }

    boolean isEmpty() { return steps.stream().allMatch(List::isEmpty); }

    /**
     * Finds a complete chain that uses {@code anchor} as step {@code i}: greedy latest-before for earlier steps and
     * earliest-after for later steps minimises the span, so if this chain is too long no valid chain exists.
     * A found chain is consumed (its events cannot complete another sequence).
     */
    List<Entry> completeWith(int i, Entry anchor, Duration within) {
        int n = steps.size();
        Entry[] chosen = new Entry[n];
        chosen[i] = anchor;
        Set<String> used = new HashSet<>();
        used.add(anchor.eventId());
        for (int j = i - 1; j >= 0; j--) {
            Entry best = null;
            for (Entry e : steps.get(j))
                if (!e.time().isAfter(chosen[j + 1].time()) && !used.contains(e.eventId())) best = e; // sorted asc: keep last
            if (best == null) return null;
            chosen[j] = best;
            used.add(best.eventId());
        }
        for (int j = i + 1; j < n; j++) {
            Entry best = null;
            for (Entry e : steps.get(j))
                if (!e.time().isBefore(chosen[j - 1].time()) && !used.contains(e.eventId())) { best = e; break; }
            if (best == null) return null;
            chosen[j] = best;
            used.add(best.eventId());
        }
        if (Duration.between(chosen[0].time(), chosen[n - 1].time()).compareTo(within) > 0) return null;
        for (Entry e : chosen) remove(e.eventId());
        return List.of(chosen);
    }
}
