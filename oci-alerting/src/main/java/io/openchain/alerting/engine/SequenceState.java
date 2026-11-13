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
    boolean add(int step, Entry e, int maxPerStep) { return add(step, e, maxPerStep, null); }

    /** Same; {@code undo} (nullable) receives the compensation. */
    boolean add(int step, Entry e, int maxPerStep, UndoLog undo) {
        List<Entry> l = steps.get(step);
        int idx = 0;
        while (idx < l.size() && !l.get(idx).time().isAfter(e.time())) idx++;
        l.add(idx, e);
        Entry dropped = l.size() > maxPerStep ? l.removeFirst() : null;
        if (undo != null) undo.push(() -> { l.remove(e); if (dropped != null) l.addFirst(dropped); });
        return dropped != null;
    }

    boolean remove(String eventId) {
        boolean any = false;
        for (List<Entry> l : steps) any |= l.removeIf(e -> e.eventId().equals(eventId));
        return any;
    }

    void evictUpTo(Instant limit) { evictUpTo(limit, null); }

    void evictUpTo(Instant limit, UndoLog undo) {
        for (List<Entry> l : steps) {
            int n = 0; // sorted by time: the evicted entries are a prefix
            while (n < l.size() && !l.get(n).time().isAfter(limit)) n++;
            if (n == 0) continue;
            List<Entry> removed = new ArrayList<>(l.subList(0, n));
            l.subList(0, n).clear();
            if (undo != null) undo.push(() -> l.addAll(0, removed));
        }
    }

    /** Removes every entry of the event, recording each removal (position and entry) so it can be put back exactly. */
    private void remove(String eventId, UndoLog undo) {
        for (List<Entry> l : steps) {
            for (int k = l.size() - 1; k >= 0; k--) {
                if (!l.get(k).eventId().equals(eventId)) continue;
                Entry x = l.remove(k);
                int at = k;
                if (undo != null) undo.push(() -> l.add(at, x)); // popped in ascending position order: exact restore
            }
        }
    }

    boolean isEmpty() { return steps.stream().allMatch(List::isEmpty); }

    /**
     * Finds a complete chain that uses {@code anchor} as step {@code i}: greedy latest-before for earlier steps and
     * earliest-after for later steps minimises the span, so if this chain is too long no valid chain exists.
     * A found chain is consumed (its events cannot complete another sequence).
     */
    List<Entry> completeWith(int i, Entry anchor, Duration within) { return completeWith(i, anchor, within, null); }

    List<Entry> completeWith(int i, Entry anchor, Duration within, UndoLog undo) {
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
        for (Entry e : chosen) remove(e.eventId(), undo);
        return List.of(chosen);
    }
}
