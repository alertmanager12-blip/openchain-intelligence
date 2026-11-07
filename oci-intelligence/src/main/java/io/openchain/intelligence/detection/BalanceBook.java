package io.openchain.intelligence.detection;

import io.openchain.core.model.Network;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real balances observed by the ingestion: for an outgoing value flow, the sender's balance of that token at the end of
 * the block BEFORE the flow, as reported by the provider. On an <em>authoritative</em> network (a real chain), detectors
 * that reason about a balance use only these values and stay silent when one is missing: the platform only sees the
 * flows indexed since its start, so a balance estimated from them is wrong for any wallet funded before (every spender
 * would look "drained"). Simulated demo chains are not authoritative: their flow-based estimate is exact (the demo ledger
 * funds every wallet in indexed blocks). Bounded (most recent {@link #MAX_ENTRIES} flows).
 */
public final class BalanceBook {

    public static final int MAX_ENTRIES = 200_000;

    private final Set<Network> authoritative = ConcurrentHashMap.newKeySet();
    private final Map<String, BigDecimal> before = Collections.synchronizedMap(new LinkedHashMap<>(1024, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, BigDecimal> eldest) { return size() > MAX_ENTRIES; }
    });

    /** Marks a network whose balances must come from the provider (real chain). */
    public void markAuthoritative(Network n) { authoritative.add(n); }

    public boolean authoritative(Network n) { return authoritative.contains(n); }

    /** Records the sender's real balance (human units) before the flow {@code eventId}. */
    public void recordSenderBalanceBefore(String eventId, BigDecimal balance) { before.put(eventId, balance); }

    public Optional<BigDecimal> senderBalanceBefore(String eventId) { return Optional.ofNullable(before.get(eventId)); }
}
