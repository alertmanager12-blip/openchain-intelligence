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
     * @param explanation  technical trace of the computation (factor, confidence, decay, q, p)
     * @param title        plain-language name of the factor, e.g. "Rapid drain", "Listed as sanctioned"
     * @param summary      plain-language sentence: what was observed (signal) or who lists the address (label)
     */
    public record Contribution(String factor, String signalId, String source, double weight, double confidence,
                               long ageSeconds, double points, String explanation, String title, String summary) {
        public Contribution(String factor, String signalId, String source, double weight, double confidence,
                            long ageSeconds, double points, String explanation) {
            this(factor, signalId, source, weight, confidence, ageSeconds, points, explanation, null, null);
        }
    }
}
