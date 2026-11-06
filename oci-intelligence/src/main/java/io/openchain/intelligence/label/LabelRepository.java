package io.openchain.intelligence.label;

import io.openchain.core.model.Address;
import java.util.Collection;
import java.util.List;

public interface LabelRepository {
    /** Upsert by {@link AddressLabel#key()}: a newer observation from the same source replaces the older one. */
    void save(AddressLabel label);

    default void saveAll(Collection<AddressLabel> labels) { labels.forEach(this::save); }

    List<AddressLabel> findByAddress(Address address);

    /**
     * Removes the assertion of {@code source} about {@code address} in {@code category} (e.g. an issuer unfreeze).
     *
     * @return true if a label was removed
     */
    boolean remove(Address address, LabelCategory category, String source);

    /** Labels of risky categories only. */
    default List<AddressLabel> findRisky(Address address) {
        return findByAddress(address).stream().filter(l -> l.category().risky()).toList();
    }

    int size();
}
