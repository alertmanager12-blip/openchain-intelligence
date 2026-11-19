package io.openchain.intelligence.flow;

import io.openchain.core.model.Address;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Thread-safe in-memory {@link FlowStore}. Suitable for tests, demos and single-node deployments. */
public final class InMemoryFlowStore implements FlowStore {
    /** Flow by event id: the same cost as a set of ids (a HashSet is a HashMap), and removal by id needs no scan. */
    private final Map<String, ValueFlow> byId = new HashMap<>();
    private final Map<Address, List<ValueFlow>> out = new HashMap<>();
    private final Map<Address, List<ValueFlow>> in = new HashMap<>();

    @Override public synchronized boolean add(ValueFlow flow) {
        if (byId.putIfAbsent(flow.eventId(), flow) != null) return false;
        insertSorted(out.computeIfAbsent(flow.from(), k -> new ArrayList<>()), flow);
        insertSorted(in.computeIfAbsent(flow.to(), k -> new ArrayList<>()), flow);
        return true;
    }

    private static void insertSorted(List<ValueFlow> list, ValueFlow f) {
        int i = list.size();
        while (i > 0 && FlowOrder.CHRONOLOGICAL.compare(list.get(i - 1), f) > 0) i--;
        list.add(i, f);
    }

    @Override public synchronized boolean contains(String eventId) { return byId.containsKey(eventId); }

    @Override public synchronized int evictBefore(java.util.function.Function<io.openchain.core.model.Network, java.time.Instant> cutoffOf,
                                                  java.util.function.Predicate<Address> keep) {
        java.util.Set<String> gone = new HashSet<>();
        for (var lists : List.of(out, in)) {
            for (var it = lists.entrySet().iterator(); it.hasNext(); ) {
                List<ValueFlow> l = it.next().getValue();
                l.removeIf(f -> {
                    java.time.Instant cutoff = cutoffOf.apply(f.network());
                    boolean old = cutoff != null && f.timestamp() != null && f.timestamp().isBefore(cutoff)
                            && !keep.test(f.from()) && !keep.test(f.to());
                    if (old) gone.add(f.eventId());
                    return old;
                });
                if (l.isEmpty()) it.remove();
            }
        }
        byId.keySet().removeAll(gone);
        return gone.size();
    }

    @Override public synchronized int evictOldest(int max, java.util.function.Predicate<Address> keep) {
        // every flow is in exactly one outgoing list
        long[] times = out.values().stream().flatMap(List::stream)
                .filter(f -> f.timestamp() != null && !keep.test(f.from()) && !keep.test(f.to()))
                .mapToLong(f -> f.timestamp().toEpochMilli()).sorted().toArray();
        java.time.Instant cutoff = io.openchain.intelligence.memory.Caps.cutoff(times, max);
        return cutoff == null ? 0 : evictBefore(n -> cutoff, keep);
    }

    /** Removes one flow in time proportional to the flows of its two parties (was a scan of every stored flow). */
    @Override public synchronized java.util.Optional<ValueFlow> remove(String eventId) {
        ValueFlow f = byId.remove(eventId);
        if (f == null) return java.util.Optional.empty();
        removeFrom(out, f.from(), eventId);
        removeFrom(in, f.to(), eventId);
        return java.util.Optional.of(f);
    }

    private static void removeFrom(Map<Address, List<ValueFlow>> lists, Address a, String eventId) {
        List<ValueFlow> l = lists.get(a);
        if (l == null) return;
        l.removeIf(x -> x.eventId().equals(eventId));
        if (l.isEmpty()) lists.remove(a);
    }

    @Override public synchronized int size() { return byId.size(); }
    @Override public synchronized List<ValueFlow> all() { return List.copyOf(byId.values()); }
    @Override public synchronized List<ValueFlow> outgoing(Address a) { return List.copyOf(out.getOrDefault(a, List.of())); }
    @Override public synchronized List<ValueFlow> incoming(Address a) { return List.copyOf(in.getOrDefault(a, List.of())); }

    /** Copies only the {@code max} newest flows, not the whole list. */
    @Override public synchronized List<ValueFlow> recentIncoming(Address a, int max) {
        List<ValueFlow> l = in.get(a);
        if (l == null || max <= 0) return List.of();
        List<ValueFlow> out = new java.util.ArrayList<>(Math.min(max, l.size()));
        for (int i = l.size() - 1; i >= 0 && out.size() < max; i--) out.add(l.get(i));
        return out;
    }
}
