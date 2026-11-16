package io.openchain.intelligence.detection;

import io.openchain.core.detection.Signal;
import io.openchain.core.model.Address;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory signal store. Bounded per wallet ({@link #perWalletCap}, P0 item 3): a very active wallet cannot monopolise the
 * store; beyond the cap the most represented signal type gives way first (lowest severity, then oldest), so every kind of
 * evidence keeps its latest signals, and HIGH and CRITICAL signals are removed only when nothing weaker is left. Every
 * removal is counted by reason, severity and type.
 */
public final class InMemorySignalRepository implements SignalRepository {
    /** Default signals kept per wallet. */
    public static final int DEFAULT_PER_WALLET_CAP = 64;
    private static final java.util.Comparator<Signal> EVICTION_ORDER =
            java.util.Comparator.<Signal, io.openchain.core.detection.Severity>comparing(Signal::severity)
                    .thenComparing(Signal::detectedAt, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()));

    private final Map<String, Signal> byId = new LinkedHashMap<>();
    private final Map<Address, List<Signal>> byAddress = new LinkedHashMap<>();
    private final Map<String, java.util.concurrent.atomic.LongAdder> evictions = new java.util.concurrent.ConcurrentHashMap<>();
    private final int perWalletCap;

    public InMemorySignalRepository(int perWalletCap) {
        if (perWalletCap < 1) throw new IllegalArgumentException("perWalletCap must be >= 1");
        this.perWalletCap = perWalletCap;
    }

    public InMemorySignalRepository() { this(DEFAULT_PER_WALLET_CAP); }

    /** @return false if the signal was already stored, or if it was itself the one removed by the per-wallet cap (counted). */
    @Override public synchronized boolean saveIfAbsent(Signal s) {
        if (byId.putIfAbsent(s.id(), s) != null) return false;
        List<Signal> l = byAddress.computeIfAbsent(s.address(), k -> new ArrayList<>());
        l.add(s);
        boolean kept = true;
        while (l.size() > perWalletCap) {
            Signal victim = perWalletVictim(l);
            l.remove(victim);
            byId.remove(victim.id());
            count(EvictionReason.PER_WALLET_CAP, victim);
            if (victim.id().equals(s.id())) kept = false;
        }
        return kept;
    }

    /**
     * Which signal of a wallet over its cap goes: HIGH/CRITICAL only when nothing weaker is left; among the candidates,
     * the most represented signal type gives way first (lowest severity, then oldest of it), so every kind of evidence
     * keeps its most recent signals. Removing by severity alone wiped whole categories (all fan-out and unusual-amount
     * signals of a busy wallet), see docs/bench/SIGNAL_CAP_REPORT.md.
     */
    private static Signal perWalletVictim(List<Signal> l) {
        List<Signal> pool = l.stream().filter(x -> x.severity().compareTo(io.openchain.core.detection.Severity.HIGH) < 0).toList();
        if (pool.isEmpty()) pool = l;
        Map<io.openchain.core.detection.SignalType, Long> perType = pool.stream()
                .collect(java.util.stream.Collectors.groupingBy(Signal::type, java.util.stream.Collectors.counting()));
        io.openchain.core.detection.SignalType crowded = perType.entrySet().stream()
                .max(Map.Entry.<io.openchain.core.detection.SignalType, Long>comparingByValue().thenComparing(e -> e.getKey().name()))
                .orElseThrow().getKey();
        return pool.stream().filter(x -> x.type() == crowded).min(EVICTION_ORDER).orElseThrow();
    }

    private void count(EvictionReason reason, Signal s) {
        evictions.computeIfAbsent(reason + "|" + s.severity() + "|" + s.type(), k -> new java.util.concurrent.atomic.LongAdder()).increment();
    }

    @Override public Map<String, Long> evictionCounts() {
        Map<String, Long> m = new java.util.TreeMap<>();
        evictions.forEach((k, v) -> m.put(k, v.sum()));
        return m;
    }

    @Override public synchronized int evictToCap(int max, java.util.function.Predicate<Signal> exempt, java.util.function.ToIntFunction<Signal> tier) {
        List<Signal> candidates = byId.values().stream().filter(x -> !exempt.test(x)).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        int excess = candidates.size() - Math.max(0, max);
        if (excess <= 0) return 0;
        candidates.sort(java.util.Comparator.<Signal>comparingInt(tier).thenComparing(EVICTION_ORDER));
        for (Signal x : candidates.subList(0, excess)) {
            byId.remove(x.id());
            List<Signal> l = byAddress.get(x.address());
            if (l != null) { l.removeIf(y -> y.id().equals(x.id())); if (l.isEmpty()) byAddress.remove(x.address()); }
            int t = tier.applyAsInt(x);
            count(t <= 0 ? EvictionReason.GLOBAL_CAP_NO_PROFILE : t == 1 ? EvictionReason.GLOBAL_CAP_LOW_SEVERITY
                    : EvictionReason.GLOBAL_CAP_HIGH_SEVERITY, x);
        }
        return excess;
    }
    @Override public synchronized Optional<Signal> findById(String id) { return Optional.ofNullable(byId.get(id)); }
    @Override public synchronized List<Signal> findAll() { return List.copyOf(byId.values()); }
    @Override public synchronized List<Signal> findByAddress(Address a) { return List.copyOf(byAddress.getOrDefault(a, List.of())); }
    @Override public synchronized int size() { return byId.size(); }

    /** Linear scan: called only on chain reorganisations, which are rare (0 in the 2026-11-10 production measurement). */
    @Override public synchronized List<Signal> findByEvidenceReference(String txHash) {
        if (txHash == null) return List.of();
        return byId.values().stream().filter(s -> txHash.equals(s.txHash())
                || s.evidence().stream().anyMatch(e -> txHash.equals(e.reference()))).toList();
    }

    @Override public synchronized Optional<Signal> remove(String id) {
        Signal s = byId.remove(id);
        if (s == null) return Optional.empty();
        List<Signal> l = byAddress.get(s.address());
        if (l != null) { l.removeIf(x -> x.id().equals(id)); if (l.isEmpty()) byAddress.remove(s.address()); }
        return Optional.of(s);
    }

    @Override public synchronized int evictBefore(java.time.Instant cutoff, java.util.function.Predicate<Signal> keep) {
        int before = byId.size();
        byId.values().removeIf(s -> {
            boolean old = s.detectedAt() != null && s.detectedAt().isBefore(cutoff) && !keep.test(s);
            if (old) count(EvictionReason.TIME_WINDOW, s);
            return old;
        });
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
