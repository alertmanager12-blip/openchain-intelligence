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

    /**
     * Signals that cite this transaction as evidence (the signal's own transaction or the reference of any evidence item),
     * whatever their subject address: what a chain reorganisation of that transaction may invalidate.
     */
    default List<Signal> findByEvidenceReference(String txHash) { return List.of(); }

    /** Removes one signal by id; returns it if it was stored. */
    default Optional<Signal> remove(String id) { return Optional.empty(); }

    /** Removes the signals detected before {@code cutoff} unless {@code keep} holds; returns how many were removed. */
    default int evictBefore(java.time.Instant cutoff, java.util.function.Predicate<Signal> keep) { return 0; }

    /** Removes the oldest signals that are not kept until at most {@code max} removable ones remain (ADR-0024 step 3b). */
    default int evictOldest(int max, java.util.function.Predicate<Signal> keep) { return 0; }
}
