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

    /** Every stored signal (snapshot; diagnostics and tests). */
    default List<Signal> findAll() { return List.of(); }

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

    /** Why a signal was removed from the working set (metrics: nothing is removed silently). */
    enum EvictionReason { TIME_WINDOW, PER_WALLET_CAP, GLOBAL_CAP_NO_PROFILE, GLOBAL_CAP_LOW_SEVERITY, GLOBAL_CAP_HIGH_SEVERITY }

    /**
     * Brings the signals that are not {@code exempt} down to exactly {@code max} (ADR-0024 step 3b, P0 item 3), removing
     * them in ascending order of {@code tier} (0 first), then lowest severity, then oldest. Tiers used by the trim:
     * 0 = INFO..MEDIUM of a wallet without a profile, 1 = INFO..MEDIUM of a profiled wallet, 2 = HIGH/CRITICAL of a wallet
     * without a profile, 3 = HIGH/CRITICAL of a profiled wallet (reasons: 0 NO_PROFILE, 1 LOW_SEVERITY, 2-3 HIGH_SEVERITY).
     * Every removal is counted ({@link #evictionCounts()}).
     */
    default int evictToCap(int max, java.util.function.Predicate<Signal> exempt, java.util.function.ToIntFunction<Signal> tier) { return 0; }

    /** Removals so far, keyed {@code reason|severity|type}. */
    default java.util.Map<String, Long> evictionCounts() { return java.util.Map.of(); }
}
