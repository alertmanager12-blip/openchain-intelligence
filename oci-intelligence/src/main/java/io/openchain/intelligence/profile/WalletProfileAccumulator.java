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
 */
public final class WalletProfileAccumulator {
    private final Address address;
    private final Set<String> seenEvents = new HashSet<>();
    private final Set<String> txHashes = new HashSet<>();
    private final Map<String, Tok> tokens = new LinkedHashMap<>();
    private final Map<Address, long[]> counterparties = new HashMap<>(); // [received, sent]
    private final TreeMap<Instant, Integer> activity = new TreeMap<>();
    private final Set<Address> contracts = new HashSet<>();
    private long inbound, outbound, contractCalls;

    private static final class Tok {
        final String symbol; final int decimals;
        BigInteger in = BigInteger.ZERO, out = BigInteger.ZERO; long inCount, outCount;
        Tok(String symbol, int decimals) { this.symbol = symbol; this.decimals = decimals; }
    }

    public WalletProfileAccumulator(Address address) { this.address = address; }

    /** @return false when the flow was already applied or does not involve this wallet. */
    public boolean apply(ValueFlow f) {
        if (!f.involves(address) || !seenEvents.add(f.eventId())) return false;
        txHashes.add(f.txHash());
        activity.merge(f.timestamp(), 1, Integer::sum);
        Tok t = tokens.computeIfAbsent(f.tokenKey(), k -> new Tok(f.token().symbol(), f.token().decimals()));
        boolean self = f.from().equals(f.to());
        if (f.to().equals(address)) { t.in = t.in.add(f.rawAmount()); t.inCount++; inbound++; }
        if (f.from().equals(address)) { t.out = t.out.add(f.rawAmount()); t.outCount++; outbound++; }
        if (!self) {
            long[] c = counterparties.computeIfAbsent(f.counterparty(address), k -> new long[2]);
            if (f.to().equals(address)) c[0]++; else c[1]++;
        }
        return true;
    }

    /** Records transaction-level facts (tx count, contract interactions). Value is applied separately as a flow. */
    public boolean apply(ChainTransaction tx) {
        boolean sender = address.equals(tx.from());
        if (!sender && !address.equals(tx.to())) return false;
        if (!seenEvents.add(tx.network().id() + ":" + tx.hash() + ":tx")) return false;
        txHashes.add(tx.hash());
        activity.merge(tx.timestamp(), 1, Integer::sum);
        if (sender && tx.contractInteraction()) {
            contractCalls++;
            if (tx.to() != null) contracts.add(tx.to());
        }
        return true;
    }

    public WalletProfile build(int topN, List<RiskScore> riskHistory, Instant now) {
        Map<String, TokenFlowStats> tk = new LinkedHashMap<>();
        tokens.forEach((k, t) -> tk.put(k, new TokenFlowStats(k, t.symbol, t.decimals, t.in, t.out, t.inCount, t.outCount)));
        Instant first = activity.isEmpty() ? null : activity.firstKey();
        Instant last = activity.isEmpty() ? null : activity.lastKey();
        Duration maxGap = Duration.ZERO;
        Instant prev = null;
        for (Instant i : activity.keySet()) {
            if (prev != null) { Duration g = Duration.between(prev, i); if (g.compareTo(maxGap) > 0) maxGap = g; }
            prev = i;
        }
        double days = first == null ? 1 : Math.max(1.0, Duration.between(first, last).toSeconds() / 86_400.0);
        List<CounterpartyStats> cps = new ArrayList<>();
        counterparties.forEach((a, c) -> cps.add(new CounterpartyStats(a, c[0] + c[1], c[0], c[1])));
        cps.sort(Comparator.comparingLong(CounterpartyStats::transfers).reversed()
                .thenComparing(c -> c.counterparty().value()));
        return new WalletProfile(address, tk, txHashes.size(), inbound, outbound, first, last,
                txHashes.size() / days, maxGap, cps.subList(0, Math.min(topN, cps.size())),
                contractCalls, contracts.size(), riskHistory, now);
    }
}
