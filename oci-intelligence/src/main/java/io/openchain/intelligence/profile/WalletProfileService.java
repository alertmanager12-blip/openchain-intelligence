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

    /**
     * Removes the profiles of the least recently active wallets that are not kept until at most {@code max} removable ones
     * remain (ADR-0024 step 3b); returns the removed addresses.
     */
    public java.util.Set<Address> evictLeastRecent(int max, java.util.function.Predicate<Address> keep) {
        java.util.List<Long> times = new java.util.ArrayList<>();
        for (var e : accumulators.entrySet()) {
            if (keep.test(e.getKey())) continue;
            java.time.Instant last;
            synchronized (e.getValue()) { last = e.getValue().lastActivity(); }
            if (last != null) times.add(last.toEpochMilli());
        }
        java.time.Instant cutoff = io.openchain.intelligence.memory.Caps.cutoff(times.stream().mapToLong(Long::longValue).sorted().toArray(), max);
        return cutoff == null ? java.util.Set.of() : evictIdle(n -> cutoff, keep);
    }

    /**
     * Reverts a flow whose block left the canonical chain, on both sides. Never creates a profile.
     * @return the number of sides reverted (0, 1 or 2); fewer than expected means the flow was no longer remembered exactly
     */
    public int revert(ValueFlow f) {
        int n = revertOn(f.from(), f);
        if (!f.to().equals(f.from())) n += revertOn(f.to(), f);
        return n;
    }

    private int revertOn(Address a, ValueFlow f) {
        WalletProfileAccumulator acc = accumulators.get(a);
        if (acc == null) return 0;
        synchronized (acc) { return !acc.retired && acc.revert(f) ? 1 : 0; }
    }

    public int size() { return accumulators.size(); }

    /**
     * The instance of {@code a} held by its profile, or {@code a} itself when it has no profile (CanonicalInstances: flows
     * then share the profile's instance; it lives as long as the profile, nothing else retains it).
     */
    public Address canonicalAddress(Address a) {
        WalletProfileAccumulator acc = accumulators.get(a);
        return acc == null ? a : acc.address();
    }

    /**
     * Orphan dedup keys (step 3 diagnostic, read-only, computed on demand): keys of profiles whose flow is no longer in the
     * flow store. Keys are copied under each profile's lock, then checked outside it (no lock is held on two structures).
     *
     * @param flowInStore whether the flow store still holds an event id
     * @param sampleMask  distinct orphan ids are sampled on {@code (hash & sampleMask) == 0} (e.g. 63: 1 in 64)
     */
    public OrphanKeyReport orphanKeys(java.util.function.Predicate<String> flowInStore, int sampleMask) {
        long t0 = System.nanoTime();
        java.time.Instant now = clock.instant();
        long profiles = 0, eventKeys = 0, txLevelKeys = 0, liveFlowKeys = 0, orphanFlowKeys = 0, orphanFlowKeyBytes = 0;
        long txKeys = 0, orphanTxKeys = 0, orphanTxKeyBytes = 0, profilesWithOrphans = 0;
        long[] perProfile = new long[OrphanKeyReport.PER_PROFILE_BUCKETS.length + 1];
        long[] byIdle = new long[OrphanKeyReport.IDLE_BUCKETS.length + 1];
        java.util.Set<String> sample = new java.util.HashSet<>();
        for (WalletProfileAccumulator acc : accumulators.values()) {
            List<String> ev, tx; java.time.Instant last;
            synchronized (acc) { if (acc.retired) continue; ev = acc.eventKeys(); tx = acc.txKeys(); last = acc.lastActivity(); }
            profiles++;
            java.util.Set<String> liveTx = new java.util.HashSet<>();
            long orphans = 0;
            for (String k : ev) {
                eventKeys++;
                if (k.endsWith(":tx")) { txLevelKeys++; continue; } // transaction-level key: never a flow id
                if (flowInStore.test(k)) {
                    liveFlowKeys++;
                    int a = k.indexOf(':'), b = k.lastIndexOf(':');
                    if (a >= 0 && b > a) liveTx.add(k.substring(a + 1, b));
                } else {
                    orphanFlowKeys++; orphans++; orphanFlowKeyBytes += OrphanKeyReport.entryBytes(k);
                    if ((k.hashCode() & sampleMask) == 0) sample.add(k);
                }
            }
            for (String h : tx) {
                txKeys++;
                if (!liveTx.contains(h)) { orphanTxKeys++; orphanTxKeyBytes += OrphanKeyReport.entryBytes(h); }
            }
            if (orphans > 0) profilesWithOrphans++;
            perProfile[OrphanKeyReport.bucket(OrphanKeyReport.PER_PROFILE_BUCKETS, orphans)]++;
            long idleSeconds = last == null ? Long.MAX_VALUE : java.time.Duration.between(last, now).toSeconds();
            byIdle[OrphanKeyReport.bucket(OrphanKeyReport.IDLE_BUCKETS, idleSeconds)] += orphans;
        }
        return new OrphanKeyReport(now, profiles, eventKeys, txLevelKeys, liveFlowKeys, orphanFlowKeys, orphanFlowKeyBytes,
                (long) sample.size() * (sampleMask + 1L), txKeys, orphanTxKeys, orphanTxKeyBytes, profilesWithOrphans,
                perProfile, byIdle, (System.nanoTime() - t0) / 1_000_000);
    }

    /** The live accumulator of an address, or null (tests). */
    WalletProfileAccumulator accumulator(Address a) { return accumulators.get(a); }

    public boolean has(Address a) { return accumulators.containsKey(a); }

    public Optional<WalletProfile> profile(Address a) {
        WalletProfileAccumulator acc = accumulators.get(a);
        if (acc == null) return Optional.empty();
        synchronized (acc) { return Optional.of(acc.build(topCounterparties, riskHistory.apply(a), clock.instant())); }
    }

    /** Profile, or an empty one (no activity) for unknown addresses. */
    public WalletProfile profileOrEmpty(Address a) { return profile(a).orElseGet(() -> WalletProfile.empty(a, clock.instant())); }
}
