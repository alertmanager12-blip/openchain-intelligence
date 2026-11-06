package io.openchain.intelligence.risk;

import io.openchain.core.model.Address;
import io.openchain.core.risk.RiskScore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Keeps the last {@code maxPerAddress} scores per address. */
public final class InMemoryRiskScoreRepository implements RiskScoreRepository {
    private final Map<Address, List<RiskScore>> data = new HashMap<>();
    private final int maxPerAddress;

    public InMemoryRiskScoreRepository(int maxPerAddress) { this.maxPerAddress = maxPerAddress; }
    public InMemoryRiskScoreRepository() { this(500); }

    @Override public synchronized void append(RiskScore s) {
        List<RiskScore> l = data.computeIfAbsent(s.address(), k -> new ArrayList<>());
        l.add(s);
        if (l.size() > maxPerAddress) l.removeFirst();
    }
    @Override public synchronized List<RiskScore> history(Address a) { return List.copyOf(data.getOrDefault(a, List.of())); }
    @Override public synchronized Optional<RiskScore> latest(Address a) {
        List<RiskScore> l = data.get(a);
        return l == null || l.isEmpty() ? Optional.empty() : Optional.of(l.getLast());
    }
}
