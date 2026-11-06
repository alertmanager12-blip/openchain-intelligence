package io.openchain.core.risk;

import io.openchain.core.model.Address;
import java.time.Instant;
import java.util.List;

/** Explainable score: every point is traceable to a contribution. */
public record RiskScore(Address address, int score, RiskLevel level, List<Contribution> contributions,
                        String method, String modelVersion, Instant computedAt) {
    public RiskScore { contributions = List.copyOf(contributions); }

    /**
     * @param weight       configured weight of the factor
     * @param points       points actually added after confidence/age decay and dedup
     * @param ageSeconds   age of the underlying information
     */
    public record Contribution(String factor, String signalId, String source, double weight, double confidence,
                               long ageSeconds, double points, String explanation) {}
}
