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
    @Override public synchronized List<ValueFlow> outgoing(Address a) { return List.copyOf(out.getOrDefault(a, List.of())); }
    @Override public synchronized List<ValueFlow> incoming(Address a) { return List.copyOf(in.getOrDefault(a, List.of())); }
}
