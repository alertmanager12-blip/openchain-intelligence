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

    /** Removes the signals detected before {@code cutoff} unless {@code keep} holds; returns how many were removed. */
    default int evictBefore(java.time.Instant cutoff, java.util.function.Predicate<Signal> keep) { return 0; }
}
