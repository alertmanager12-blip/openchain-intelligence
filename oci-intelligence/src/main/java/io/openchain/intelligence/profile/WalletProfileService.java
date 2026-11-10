package io.openchain.intelligence.profile;

import io.openchain.core.model.Address;
import io.openchain.core.model.ChainTransaction;
import io.openchain.core.model.TokenTransfer;
import io.openchain.core.risk.RiskScore;
import io.openchain.intelligence.flow.NativeTokens;
import io.openchain.intelligence.flow.ValueFlow;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Incrementally maintains wallet profiles for both sides of every observed movement.
 * Idempotent: replaying the same event (same eventId) has no effect.
 */
public final class WalletProfileService {
    private final ConcurrentHashMap<Address, WalletProfileAccumulator> accumulators = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int topCounterparties;
    private final Function<Address, List<RiskScore>> riskHistory;

    /** @param riskHistory source of the score history attached to profiles (e.g. {@code RiskScoringService::history}). */
    public WalletProfileService(Clock clock, int topCounterparties, Function<Address, List<RiskScore>> riskHistory) {
        this.clock = clock;
        this.topCounterparties = topCounterparties;
        this.riskHistory = riskHistory == null ? a -> List.of() : riskHistory;
    }

    public WalletProfileService(Clock clock) { this(clock, 10, null); }

    /** @return true if at least one profile changed. */
    public boolean ingest(TokenTransfer t) { return ingest(ValueFlow.of(t)); }

    public boolean ingest(ValueFlow f) {
        boolean changed = update(f.from(), acc -> acc.apply(f));
        if (!f.to().equals(f.from())) changed |= update(f.to(), acc -> acc.apply(f));
        return changed;
    }

    /** Applies tx-level facts and its native value (if any). */
    public boolean ingest(ChainTransaction tx) {
        boolean changed = tx.from() != null && update(tx.from(), acc -> acc.apply(tx));
        if (tx.to() != null && !tx.to().equals(tx.from())) changed |= update(tx.to(), acc -> acc.apply(tx));
        Optional<ValueFlow> nat = ValueFlow.ofNative(tx, NativeTokens.of(tx.network()));
        if (nat.isPresent()) changed |= ingest(nat.get());
        return changed;
    }

    private boolean update(Address a, Function<WalletProfileAccumulator, Boolean> op) {
        while (true) {
            WalletProfileAccumulator acc = accumulators.computeIfAbsent(a, WalletProfileAccumulator::new);
            synchronized (acc) {
                if (acc.retired) continue; // removed by evictIdle between the lookup and the lock: use a fresh one
                return op.apply(acc);
            }
        }
    }

    /**
     * Removes the profiles of wallets inactive since the cutoff of their network (latest flow older; {@code null}: none on
     * that network) that are not kept; returns the removed addresses (their scores and tracking can then be dropped too).
     */
    public java.util.Set<Address> evictIdle(java.util.function.Function<io.openchain.core.model.Network, java.time.Instant> cutoffOf,
                                            java.util.function.Predicate<Address> keep) {
        java.util.Set<Address> gone = new java.util.HashSet<>();
        for (var e : accumulators.entrySet()) {
            WalletProfileAccumulator acc = e.getValue();
            if (keep.test(e.getKey())) continue;
            java.time.Instant cutoff = cutoffOf.apply(e.getKey().network());
            if (cutoff == null) continue;
            synchronized (acc) { // no flow can be applied to it while it is checked and removed
                java.time.Instant last = acc.lastActivity();
                if (last != null && last.isBefore(cutoff) && accumulators.remove(e.getKey(), acc)) {
                    acc.retired = true;
                    gone.add(e.getKey());
                }
            }
        }
        return gone;
    }

    public int size() { return accumulators.size(); }

    public boolean has(Address a) { return accumulators.containsKey(a); }

    public Optional<WalletProfile> profile(Address a) {
        WalletProfileAccumulator acc = accumulators.get(a);
        if (acc == null) return Optional.empty();
        synchronized (acc) { return Optional.of(acc.build(topCounterparties, riskHistory.apply(a), clock.instant())); }
    }

    /** Profile, or an empty one (no activity) for unknown addresses. */
    public WalletProfile profileOrEmpty(Address a) { return profile(a).orElseGet(() -> WalletProfile.empty(a, clock.instant())); }
}
