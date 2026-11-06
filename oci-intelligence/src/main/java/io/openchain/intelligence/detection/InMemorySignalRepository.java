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
}
