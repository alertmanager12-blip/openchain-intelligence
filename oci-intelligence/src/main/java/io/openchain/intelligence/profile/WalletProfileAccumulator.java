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
    /** Recent transaction hashes with the number of applied flows of each (a transaction is uncounted with its last flow). */
    private final RecentCounts txHashes = new RecentCounts(RECENT_KEYS);
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

    /** Insertion-ordered counter that forgets its oldest keys beyond a capacity. */
    private static final class RecentCounts extends LinkedHashMap<String, Integer> {
        private final int cap;
        RecentCounts(int cap) { super(16, 0.75f, false); this.cap = cap; }
        @Override protected boolean removeEldestEntry(Map.Entry<String, Integer> e) { return size() > cap; }
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

    private void countTx(String hash) { if (txHashes.merge(hash, 1, Integer::sum) == 1) transactionCount++; }

    /**
     * Exact inverse of {@link #apply(ValueFlow)} for a flow whose block left the canonical chain. Applies only to a flow
     * this accumulator still remembers (the latest {@link #RECENT_KEYS} events): an older or unknown flow is left untouched
     * (returns false) rather than subtracting what may never have been added, e.g. after the profile was evicted and
     * restarted. Token totals, transfer counts, the transaction count and counterparties are restored exactly; an activity
     * instant is removed while it is in the exact part (not yet folded).
     *
     * @return true if the flow was reverted
     */
    public boolean revert(ValueFlow f) {
        if (!f.involves(address) || seenEvents.remove(f.eventId()) == null) return false;
        Integer n = txHashes.get(f.txHash());
        if (n != null) {
            if (n <= 1) { txHashes.remove(f.txHash()); transactionCount--; } else txHashes.put(f.txHash(), n - 1);
        }
        Integer c = activity.get(f.timestamp());
        if (c != null) { if (c <= 1) activity.remove(f.timestamp()); else activity.put(f.timestamp(), c - 1); }
        Tok t = tokens.get(f.tokenKey());
        if (t != null) {
            if (f.to().equals(address)) { t.in = t.in.subtract(f.rawAmount()); t.inCount--; inbound--; }
            if (f.from().equals(address)) { t.out = t.out.subtract(f.rawAmount()); t.outCount--; outbound--; }
            if (t.inCount == 0 && t.outCount == 0) tokens.remove(f.tokenKey());
        }
        if (!f.from().equals(f.to())) {
            long[] cp = counterparties.get(f.counterparty(address));
            if (cp != null) {
                if (f.to().equals(address)) cp[0]--; else cp[1]--;
                if (cp[0] <= 0 && cp[1] <= 0) counterparties.remove(f.counterparty(address));
            }
        }
        return true;
    }

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

    /**
     * True when this profile was created for an address already seen earlier (its previous profile left the bounded
     * working set): first activity, age and transaction count are then lower bounds, not measurements. Callers synchronise.
     */
    private boolean startUnknown;
    void markStartUnknown() { startUnknown = true; }
    boolean startUnknown() { return startUnknown; }

    /** The address this profile describes (the canonical instance shared with stored flows, see CanonicalInstances). */
    public Address address() { return address; }

    /** Copy of the remembered dedup keys (diagnostic, read-only): event ids then transaction hashes. Callers synchronise. */
    List<String> eventKeys() { return new ArrayList<>(seenEvents.keySet()); }
    List<String> txKeys() { return new ArrayList<>(txHashes.keySet()); }

    /** Entries held in the bounded collections (tests: the footprint does not grow with the number of transfers). */
    int retainedEntries() { return seenEvents.size() + txHashes.size() + activity.size() + counterparties.size() + contracts.size(); }

    // ------------------------------------------------------------ snapshot (ADR-0024 step 3d)

    /** Version of the binary format written by {@link #writeTo}. Change it with any change of the layout below. */
    public static final int SNAPSHOT_FORMAT = 1;

    /**
     * Writes the complete state, exactly: remembered keys in their insertion order (it decides which are forgotten next),
     * counts, totals, exact and folded activity, counterparties and contracts (sorted by address, so equal states give
     * equal bytes), and the "start unknown" mark. Not written: {@code retired} (a retired accumulator is never in a
     * snapshot). Callers synchronise on the accumulator.
     */
    public void writeTo(java.io.DataOutput out) throws java.io.IOException {
        out.writeByte(SNAPSHOT_FORMAT);
        io.openchain.intelligence.snapshot.SnapshotIO.writeAddress(out, address);
        out.writeInt(seenEvents.size());
        for (String k : seenEvents.keySet()) io.openchain.intelligence.snapshot.SnapshotIO.writeString(out, k);
        out.writeInt(txHashes.size());
        for (Map.Entry<String, Integer> e : txHashes.entrySet()) {
            io.openchain.intelligence.snapshot.SnapshotIO.writeString(out, e.getKey());
            out.writeInt(e.getValue());
        }
        out.writeLong(transactionCount);
        out.writeInt(tokens.size());
        for (Map.Entry<String, Tok> e : tokens.entrySet()) {
            Tok k = e.getValue();
            io.openchain.intelligence.snapshot.SnapshotIO.writeString(out, e.getKey());
            io.openchain.intelligence.snapshot.SnapshotIO.writeString(out, k.symbol);
            out.writeInt(k.decimals);
            io.openchain.intelligence.snapshot.SnapshotIO.writeBigInteger(out, k.in);
            io.openchain.intelligence.snapshot.SnapshotIO.writeBigInteger(out, k.out);
            out.writeLong(k.inCount);
            out.writeLong(k.outCount);
        }
        List<Map.Entry<Address, long[]>> cps = new ArrayList<>(counterparties.entrySet());
        cps.sort(Comparator.comparing((Map.Entry<Address, long[]> e) -> e.getKey().network().id()).thenComparing(e -> e.getKey().value()));
        out.writeInt(cps.size());
        for (Map.Entry<Address, long[]> e : cps) {
            io.openchain.intelligence.snapshot.SnapshotIO.writeAddress(out, e.getKey());
            out.writeLong(e.getValue()[0]);
            out.writeLong(e.getValue()[1]);
        }
        out.writeInt(activity.size());
        for (Map.Entry<Instant, Integer> e : activity.entrySet()) {
            io.openchain.intelligence.snapshot.SnapshotIO.writeInstant(out, e.getKey());
            out.writeInt(e.getValue());
        }
        io.openchain.intelligence.snapshot.SnapshotIO.writeInstant(out, foldedFirst);
        io.openchain.intelligence.snapshot.SnapshotIO.writeInstant(out, foldedLast);
        io.openchain.intelligence.snapshot.SnapshotIO.writeDuration(out, foldedMaxGap);
        List<Address> cs = new ArrayList<>(contracts);
        cs.sort(Comparator.comparing((Address a) -> a.network().id()).thenComparing(Address::value));
        out.writeInt(cs.size());
        for (Address a : cs) io.openchain.intelligence.snapshot.SnapshotIO.writeAddress(out, a);
        out.writeLong(inbound);
        out.writeLong(outbound);
        out.writeLong(contractCalls);
        out.writeBoolean(startUnknown);
    }

    /**
     * Reads an accumulator written by {@link #writeTo}.
     *
     * @param canonical shares equal addresses with the stored flows (CanonicalInstances); identity for none
     * @throws io.openchain.intelligence.snapshot.SnapshotIO.SnapshotFormatException on an unknown version or bad data
     */
    public static WalletProfileAccumulator readFrom(java.io.DataInput in, java.util.function.UnaryOperator<Address> canonical)
            throws java.io.IOException {
        io.openchain.intelligence.snapshot.SnapshotIO.expectVersion(in, SNAPSHOT_FORMAT, "wallet profile");
        Address address = io.openchain.intelligence.snapshot.SnapshotIO.readAddress(in, canonical);
        if (address == null) throw new io.openchain.intelligence.snapshot.SnapshotIO.SnapshotFormatException("wallet profile without address");
        WalletProfileAccumulator a = new WalletProfileAccumulator(address);
        for (int i = 0, n = count(in, RECENT_KEYS); i < n; i++) a.seenEvents.add(io.openchain.intelligence.snapshot.SnapshotIO.readString(in));
        for (int i = 0, n = count(in, RECENT_KEYS); i < n; i++) a.txHashes.put(io.openchain.intelligence.snapshot.SnapshotIO.readString(in), in.readInt());
        a.transactionCount = in.readLong();
        for (int i = 0, n = count(in, Integer.MAX_VALUE); i < n; i++) {
            String key = io.openchain.intelligence.snapshot.SnapshotIO.readString(in);
            Tok k = new Tok(io.openchain.intelligence.snapshot.SnapshotIO.readString(in), in.readInt());
            k.in = io.openchain.intelligence.snapshot.SnapshotIO.readBigInteger(in);
            k.out = io.openchain.intelligence.snapshot.SnapshotIO.readBigInteger(in);
            k.inCount = in.readLong();
            k.outCount = in.readLong();
            a.tokens.put(key, k);
        }
        for (int i = 0, n = count(in, 2 * COUNTERPARTY_CAP); i < n; i++) {
            Address c = io.openchain.intelligence.snapshot.SnapshotIO.readAddress(in, canonical);
            a.counterparties.put(c, new long[]{in.readLong(), in.readLong()});
        }
        for (int i = 0, n = count(in, ACTIVITY_CAP); i < n; i++) a.activity.put(io.openchain.intelligence.snapshot.SnapshotIO.readInstant(in), in.readInt());
        a.foldedFirst = io.openchain.intelligence.snapshot.SnapshotIO.readInstant(in);
        a.foldedLast = io.openchain.intelligence.snapshot.SnapshotIO.readInstant(in);
        a.foldedMaxGap = io.openchain.intelligence.snapshot.SnapshotIO.readDuration(in);
        for (int i = 0, n = count(in, CONTRACT_CAP); i < n; i++) a.contracts.add(io.openchain.intelligence.snapshot.SnapshotIO.readAddress(in, canonical));
        a.inbound = in.readLong();
        a.outbound = in.readLong();
        a.contractCalls = in.readLong();
        a.startUnknown = in.readBoolean();
        return a;
    }

    private static int count(java.io.DataInput in, int max) throws java.io.IOException {
        int n = in.readInt();
        if (n < 0 || n > max) throw new io.openchain.intelligence.snapshot.SnapshotIO.SnapshotFormatException("invalid count " + n + " (max " + max + ")");
        return n;
    }

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
