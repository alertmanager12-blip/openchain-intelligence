package io.openchain.intelligence.profile;

import io.openchain.core.model.Address;
import io.openchain.core.model.ChainTransaction;
import io.openchain.core.risk.RiskScore;
import io.openchain.intelligence.flow.ValueFlow;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Mutable, incremental builder of a {@link WalletProfile}. Idempotent on event id; tolerant to out-of-order events.
 * Not thread-safe: {@link WalletProfileService} serialises access per address.
 *
 * <p>Bounded memory (ADR-0024 step 3b): a wallet's footprint no longer grows with its number of transfers. Below the caps
 * every figure is exact, as before. Above them, for very active wallets (exchange hot wallets, routers):
 * <ul>
 *   <li>duplicate detection remembers the latest {@link #RECENT_KEYS} event ids and transaction hashes: a replay of an
 *       older event (beyond the reorg and restart windows, which are far shorter) would be counted again;</li>
 *   <li>activity times are exact for the latest {@link #ACTIVITY_CAP} distinct instants; older ones are folded into the
 *       first time, the end of the folded span and the longest gap inside it, so first activity stays exact and the
 *       dormancy gap can only be over-estimated by an out-of-order event older than the folded span;</li>
 *   <li>counterparties: the {@link #COUNTERPARTY_CAP} with the most transfers are kept (the profile exposes a top list
 *       only); a pruned counterparty that comes back restarts its count;</li>
 *   <li>distinct contracts are counted up to {@link #CONTRACT_CAP}, a lower bound beyond.</li>
 * </ul>
 * Token totals, transfer counts and contract-call counts stay exact.
 */
public final class WalletProfileAccumulator {
    static final int RECENT_KEYS = 4_096;
    static final int ACTIVITY_CAP = 2_048;
    static final int COUNTERPARTY_CAP = 1_024;
    static final int CONTRACT_CAP = 1_024;

    private final Address address;
    private final RecentKeys seenEvents = new RecentKeys(RECENT_KEYS);
    private final RecentKeys txHashes = new RecentKeys(RECENT_KEYS);
    private long transactionCount;
    private final Map<String, Tok> tokens = new LinkedHashMap<>();
    private final Map<Address, long[]> counterparties = new HashMap<>(); // [received, sent]
    private final TreeMap<Instant, Integer> activity = new TreeMap<>();
    /** Folded (older) activity: first instant, last instant and the longest gap between consecutive folded instants. */
    private Instant foldedFirst, foldedLast;
    private Duration foldedMaxGap = Duration.ZERO;
    private final Set<Address> contracts = new HashSet<>();
    private long inbound, outbound, contractCalls;

    private static final class Tok {
        final String symbol; final int decimals;
        BigInteger in = BigInteger.ZERO, out = BigInteger.ZERO; long inCount, outCount;
        Tok(String symbol, int decimals) { this.symbol = symbol; this.decimals = decimals; }
    }

    /** Insertion-ordered set that forgets its oldest keys beyond a capacity. */
    private static final class RecentKeys extends LinkedHashMap<String, Boolean> {
        private final int cap;
        RecentKeys(int cap) { super(16, 0.75f, false); this.cap = cap; }
        /** @return true if the key was not among the remembered ones. */
        boolean add(String k) { return put(k, Boolean.TRUE) == null; }
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > cap; }
    }

    public WalletProfileAccumulator(Address address) { this.address = address; }

    /** Set (under the accumulator lock) when the working-set trim removed it: writers then retry with a fresh one. */
    boolean retired;

    /** Time of the latest applied flow, or null. Callers synchronise on the accumulator. */
    public Instant lastActivity() { return activity.isEmpty() ? foldedLast : activity.lastKey(); }

    /** @return false when the flow was already applied or does not involve this wallet. */
    public boolean apply(ValueFlow f) {
        if (!f.involves(address) || !seenEvents.add(f.eventId())) return false;
        countTx(f.txHash());
        record(f.timestamp());
        Tok t = tokens.computeIfAbsent(f.tokenKey(), k -> new Tok(f.token().symbol(), f.token().decimals()));
        boolean self = f.from().equals(f.to());
        if (f.to().equals(address)) { t.in = t.in.add(f.rawAmount()); t.inCount++; inbound++; }
        if (f.from().equals(address)) { t.out = t.out.add(f.rawAmount()); t.outCount++; outbound++; }
        if (!self) {
            long[] c = counterparties.computeIfAbsent(f.counterparty(address), k -> new long[2]);
            if (f.to().equals(address)) c[0]++; else c[1]++;
            if (counterparties.size() > 2 * COUNTERPARTY_CAP) pruneCounterparties();
        }
        return true;
    }

    /** Records transaction-level facts (tx count, contract interactions). Value is applied separately as a flow. */
    public boolean apply(ChainTransaction tx) {
        boolean sender = address.equals(tx.from());
        if (!sender && !address.equals(tx.to())) return false;
        if (!seenEvents.add(tx.network().id() + ":" + tx.hash() + ":tx")) return false;
        countTx(tx.hash());
        record(tx.timestamp());
        if (sender && tx.contractInteraction()) {
            contractCalls++;
            if (tx.to() != null && contracts.size() < CONTRACT_CAP) contracts.add(tx.to());
        }
        return true;
    }

    private void countTx(String hash) { if (txHashes.add(hash)) transactionCount++; }

    private void record(Instant t) {
        if (foldedLast != null && !t.isAfter(foldedLast)) { // inside the folded span: only the first time can move
            if (t.isBefore(foldedFirst)) { gap(t, foldedFirst); foldedFirst = t; } // nothing lies between them: exact
            return;
        }
        activity.merge(t, 1, Integer::sum);
        if (activity.size() > ACTIVITY_CAP) fold(ACTIVITY_CAP / 2);
    }

    /** Moves the oldest {@code n} activity instants into the folded summary. */
    private void fold(int n) {
        for (int i = 0; i < n && !activity.isEmpty(); i++) {
            Instant k = activity.pollFirstEntry().getKey();
            if (foldedLast == null) foldedFirst = k;
            else gap(foldedLast, k);
            foldedLast = k;
        }
    }

    private void gap(Instant a, Instant b) {
        Duration g = Duration.between(a, b);
        if (g.compareTo(foldedMaxGap) > 0) foldedMaxGap = g;
    }

    /** Keeps the {@link #COUNTERPARTY_CAP} counterparties with the most transfers (ties: address order, deterministic). */
    private void pruneCounterparties() {
        List<Map.Entry<Address, long[]>> all = new ArrayList<>(counterparties.entrySet());
        all.sort(Comparator.<Map.Entry<Address, long[]>>comparingLong(e -> e.getValue()[0] + e.getValue()[1]).reversed()
                .thenComparing(e -> e.getKey().value()));
        for (int i = COUNTERPARTY_CAP; i < all.size(); i++) counterparties.remove(all.get(i).getKey());
    }

    /** Entries held in the bounded collections (tests: the footprint does not grow with the number of transfers). */
    int retainedEntries() { return seenEvents.size() + txHashes.size() + activity.size() + counterparties.size() + contracts.size(); }

    public WalletProfile build(int topN, List<RiskScore> riskHistory, Instant now) {
        Map<String, TokenFlowStats> tk = new LinkedHashMap<>();
        tokens.forEach((k, t) -> tk.put(k, new TokenFlowStats(k, t.symbol, t.decimals, t.in, t.out, t.inCount, t.outCount)));
        Instant first = foldedFirst != null ? foldedFirst : activity.isEmpty() ? null : activity.firstKey();
        Instant last = lastActivity();
        Duration maxGap = foldedMaxGap;
        Instant prev = foldedLast;
        for (Instant i : activity.keySet()) {
            if (prev != null) { Duration g = Duration.between(prev, i); if (g.compareTo(maxGap) > 0) maxGap = g; }
            prev = i;
        }
        double days = first == null ? 1 : Math.max(1.0, Duration.between(first, last).toSeconds() / 86_400.0);
        List<CounterpartyStats> cps = new ArrayList<>();
        counterparties.forEach((a, c) -> cps.add(new CounterpartyStats(a, c[0] + c[1], c[0], c[1])));
        cps.sort(Comparator.comparingLong(CounterpartyStats::transfers).reversed()
                .thenComparing(c -> c.counterparty().value()));
        return new WalletProfile(address, tk, transactionCount, inbound, outbound, first, last,
                transactionCount / days, maxGap, cps.subList(0, Math.min(topN, cps.size())),
                contractCalls, contracts.size(), riskHistory, now);
    }
}
