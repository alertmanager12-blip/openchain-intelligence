package io.openchain.intelligence.detection;

import io.openchain.core.detection.Signal;
import io.openchain.core.model.Address;
import java.util.List;
import java.util.Optional;

public interface SignalRepository {
    /** @return true if stored; false if a signal with the same id exists (duplicate). */
    boolean saveIfAbsent(Signal signal);

    Optional<Signal> findById(String id);

    List<Signal> findByAddress(Address address);

    int size();
}
