package io.openchain.intelligence.detection;

import io.openchain.core.detection.Signal;
import io.openchain.core.model.Address;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InMemorySignalRepository implements SignalRepository {
    private final Map<String, Signal> byId = new LinkedHashMap<>();
    private final Map<Address, List<Signal>> byAddress = new LinkedHashMap<>();

    @Override public synchronized boolean saveIfAbsent(Signal s) {
        if (byId.putIfAbsent(s.id(), s) != null) return false;
        byAddress.computeIfAbsent(s.address(), k -> new ArrayList<>()).add(s);
        return true;
    }
    @Override public synchronized Optional<Signal> findById(String id) { return Optional.ofNullable(byId.get(id)); }
    @Override public synchronized List<Signal> findByAddress(Address a) { return List.copyOf(byAddress.getOrDefault(a, List.of())); }
    @Override public synchronized int size() { return byId.size(); }

    @Override public synchronized int evictBefore(java.time.Instant cutoff, java.util.function.Predicate<Signal> keep) {
        int before = byId.size();
        byId.values().removeIf(s -> s.detectedAt() != null && s.detectedAt().isBefore(cutoff) && !keep.test(s));
        for (var it = byAddress.entrySet().iterator(); it.hasNext(); ) {
            List<Signal> l = it.next().getValue();
            l.removeIf(s -> !byId.containsKey(s.id()));
            if (l.isEmpty()) it.remove();
        }
        return before - byId.size();
    }

    @Override public synchronized int evictOldest(int max, java.util.function.Predicate<Signal> keep) {
        long[] times = byId.values().stream().filter(s -> s.detectedAt() != null && !keep.test(s))
                .mapToLong(s -> s.detectedAt().toEpochMilli()).sorted().toArray();
        java.time.Instant cutoff = io.openchain.intelligence.memory.Caps.cutoff(times, max);
        return cutoff == null ? 0 : evictBefore(cutoff, keep);
    }
}
