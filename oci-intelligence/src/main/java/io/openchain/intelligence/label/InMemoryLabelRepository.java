package io.openchain.intelligence.label;

import io.openchain.core.model.Address;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryLabelRepository implements LabelRepository {
    private final Map<Address, Map<String, AddressLabel>> byAddress = new ConcurrentHashMap<>();
    /** Keys of risky labels (the category is part of the key, so a key never changes riskiness). */
    private final java.util.Set<String> riskyKeys = ConcurrentHashMap.newKeySet();

    @Override public void save(AddressLabel l) {
        byAddress.computeIfAbsent(l.address(), k -> new ConcurrentHashMap<>())
                .merge(l.key(), l, (old, neu) -> neu.observedAt().isBefore(old.observedAt()) ? old : neu);
        if (l.category().risky()) riskyKeys.add(l.key());
    }

    @Override public boolean remove(Address a, LabelCategory category, String source) {
        var m = byAddress.get(a);
        String key = a + "|" + category + "|" + source;
        boolean removed = m != null && m.remove(key) != null;
        if (removed) riskyKeys.remove(key);
        return removed;
    }

    @Override public List<AddressLabel> findByAddress(Address a) {
        var m = byAddress.get(a);
        if (m == null) return List.of();
        var list = new ArrayList<>(m.values());
        list.sort((x, y) -> x.key().compareTo(y.key()));
        return List.copyOf(list);
    }

    @Override public boolean hasRisky() { return !riskyKeys.isEmpty(); }

    @Override public int size() { return byAddress.values().stream().mapToInt(Map::size).sum(); }
}
