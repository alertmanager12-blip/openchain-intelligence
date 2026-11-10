package io.openchain.intelligence.flow;

import io.openchain.core.model.Address;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Thread-safe in-memory {@link FlowStore}. Suitable for tests, demos and single-node deployments. */
public final class InMemoryFlowStore implements FlowStore {
    private final Set<String> ids = new HashSet<>();
    private final Map<Address, List<ValueFlow>> out = new HashMap<>();
    private final Map<Address, List<ValueFlow>> in = new HashMap<>();

    @Override public synchronized boolean add(ValueFlow flow) {
        if (!ids.add(flow.eventId())) return false;
        insertSorted(out.computeIfAbsent(flow.from(), k -> new ArrayList<>()), flow);
        insertSorted(in.computeIfAbsent(flow.to(), k -> new ArrayList<>()), flow);
        return true;
    }

    private static void insertSorted(List<ValueFlow> list, ValueFlow f) {
        int i = list.size();
        while (i > 0 && FlowOrder.CHRONOLOGICAL.compare(list.get(i - 1), f) > 0) i--;
        list.add(i, f);
    }

    @Override public synchronized boolean contains(String eventId) { return ids.contains(eventId); }

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
        ids.removeAll(gone);
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

    @Override public synchronized int size() { return ids.size(); }
    @Override public synchronized List<ValueFlow> outgoing(Address a) { return List.copyOf(out.getOrDefault(a, List.of())); }
    @Override public synchronized List<ValueFlow> incoming(Address a) { return List.copyOf(in.getOrDefault(a, List.of())); }
}
