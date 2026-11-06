package io.openchain.intelligence.label;

import io.openchain.core.model.Address;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryLabelRepository implements LabelRepository {
    private final Map<Address, Map<String, AddressLabel>> byAddress = new ConcurrentHashMap<>();

    @Override public void save(AddressLabel l) {
        byAddress.computeIfAbsent(l.address(), k -> new ConcurrentHashMap<>())
                .merge(l.key(), l, (old, neu) -> neu.observedAt().isBefore(old.observedAt()) ? old : neu);
    }

    @Override public boolean remove(Address a, LabelCategory category, String source) {
        var m = byAddress.get(a);
        return m != null && m.remove(a + "|" + category + "|" + source) != null;
    }

    @Override public List<AddressLabel> findByAddress(Address a) {
        var m = byAddress.get(a);
        if (m == null) return List.of();
        var list = new ArrayList<>(m.values());
        list.sort((x, y) -> x.key().compareTo(y.key()));
        return List.copyOf(list);
    }

    @Override public int size() { return byAddress.values().stream().mapToInt(Map::size).sum(); }
}
