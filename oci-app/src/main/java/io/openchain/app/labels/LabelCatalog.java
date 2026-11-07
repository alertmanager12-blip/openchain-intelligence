package io.openchain.app.labels;

import io.openchain.core.model.Address;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.InMemoryLabelRepository;
import io.openchain.intelligence.label.LabelRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link LabelRepository} used by the intelligence pipeline, plus a listing of every label (the module port has
 * no "find all"; the API needs one for {@code GET /labels}).
 */
public final class LabelCatalog implements LabelRepository {

    private final InMemoryLabelRepository delegate = new InMemoryLabelRepository();
    private final Map<String, AddressLabel> all = new ConcurrentHashMap<>();

    @Override public void save(AddressLabel label) {
        delegate.save(label);
        all.merge(label.key(), label, (old, neu) -> neu.observedAt().isBefore(old.observedAt()) ? old : neu);
    }

    @Override public boolean remove(Address address, io.openchain.intelligence.label.LabelCategory category, String source) {
        boolean removed = delegate.remove(address, category, source);
        all.remove(address + "|" + category + "|" + source);
        return removed;
    }

    @Override public List<AddressLabel> findByAddress(Address address) { return delegate.findByAddress(address); }

    @Override public int size() { return delegate.size(); }

    @Override public boolean hasRisky() { return delegate.hasRisky(); }

    /** Every label, ordered by (network, address, category, source). */
    public List<AddressLabel> all() {
        List<AddressLabel> list = new ArrayList<>(all.values());
        list.sort(Comparator.comparing((AddressLabel l) -> l.address().network())
                .thenComparing(l -> l.address().value()).thenComparing(AddressLabel::key));
        return list;
    }
}
