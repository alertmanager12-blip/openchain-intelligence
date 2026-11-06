package io.openchain.core.risk;

public enum RiskLevel {
    UNKNOWN, LOW, MEDIUM, HIGH, CRITICAL;

    /** Default banding; UNKNOWN is used when there is not enough data, never for score 0. */
    public static RiskLevel fromScore(int score) {
        if (score < 0 || score > 100) throw new IllegalArgumentException("score must be 0..100");
        if (score >= 85) return CRITICAL;
        if (score >= 65) return HIGH;
        if (score >= 35) return MEDIUM;
        return LOW;
    }
}
