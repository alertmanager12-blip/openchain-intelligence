package io.openchain.intelligence.risk;

import io.openchain.core.detection.Severity;
import io.openchain.core.detection.SignalType;
import io.openchain.intelligence.label.LabelCategory;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Risk scoring parameters.
 *
 * @param weights            factor key -> weight in [0,1] = maximum share of the score that factor alone can reach
 *                           (keys: {@code label:<LabelCategory>}, {@code signal:<SignalType>}); it caps the factor
 * @param defaultSignalWeight weight of signal types not listed
 * @param halfLife           time-decay half-life applied to the age of the underlying information
 * @param nonDecayingFactors factors that do not decay (e.g. an active sanctions listing)
 * @param severityMultipliers signal severity -> multiplier in [0,1]
 * @param modelVersion       recorded on every score
 */
public record ScoringConfig(Map<String, Double> weights, double defaultSignalWeight, Duration halfLife,
                            Set<String> nonDecayingFactors, Map<Severity, Double> severityMultipliers, String modelVersion) {

    public ScoringConfig {
        weights = Map.copyOf(weights);
        nonDecayingFactors = Set.copyOf(nonDecayingFactors);
        severityMultipliers = Map.copyOf(severityMultipliers);
        for (var e : weights.entrySet())
            if (!(e.getValue() >= 0 && e.getValue() <= 1)) throw new IllegalArgumentException("weight out of [0,1]: " + e.getKey());
        if (halfLife.isNegative() || halfLife.isZero()) throw new IllegalArgumentException("halfLife must be > 0");
    }

    public static String labelFactor(LabelCategory c) { return "label:" + c; }
    public static String signalFactor(SignalType t) { return "signal:" + t; }

    public double weight(String factor) {
        Double w = weights.get(factor);
        if (w != null) return w;
        return factor.startsWith("signal:") ? defaultSignalWeight : 0.0;
    }

    public double severityMultiplier(Severity s) { return severityMultipliers.getOrDefault(s, 1.0); }

    public static ScoringConfig defaults() {
        Map<String, Double> w = new HashMap<>();
        w.put(labelFactor(LabelCategory.SANCTIONED), 0.95);
        w.put(labelFactor(LabelCategory.RANSOMWARE), 0.85);
        w.put(labelFactor(LabelCategory.STOLEN_FUNDS), 0.80);
        w.put(labelFactor(LabelCategory.DARKNET_MARKET), 0.80);
        w.put(labelFactor(LabelCategory.MIXER), 0.70);
        w.put(labelFactor(LabelCategory.SCAM), 0.70);
        w.put(labelFactor(LabelCategory.PHISHING), 0.70);
        w.put(labelFactor(LabelCategory.FLAGGED_COMMUNITY), 0.40);
        for (LabelCategory c : LabelCategory.values()) w.putIfAbsent(labelFactor(c), 0.0);
        w.put(signalFactor(SignalType.SANCTIONED_EXPOSURE), 0.80);
        w.put(signalFactor(SignalType.MIXER_EXPOSURE), 0.50);
        w.put(signalFactor(SignalType.SCAM_EXPOSURE), 0.50);
        w.put(signalFactor(SignalType.FLAGGED_EXPOSURE), 0.40);
        w.put(signalFactor(SignalType.INDIRECT_EXPOSURE), 0.35);
        w.put(signalFactor(SignalType.STRUCTURING), 0.35);
        w.put(signalFactor(SignalType.FAST_FORWARDING), 0.30);
        w.put(signalFactor(SignalType.RAPID_DRAIN), 0.30);
        w.put(signalFactor(SignalType.CIRCULAR_FLOW), 0.25);
        w.put(signalFactor(SignalType.FLOW_CONCENTRATION), 0.20);
        w.put(signalFactor(SignalType.OWNERSHIP_CHANGE), 0.20);
        w.put(signalFactor(SignalType.UNUSUAL_APPROVAL), 0.15);
        w.put(signalFactor(SignalType.DORMANT_REACTIVATION), 0.10);
        w.put(signalFactor(SignalType.UNUSUAL_AMOUNT), 0.10);
        Map<Severity, Double> sev = new EnumMap<>(Severity.class);
        sev.put(Severity.INFO, 0.1); sev.put(Severity.LOW, 0.4); sev.put(Severity.MEDIUM, 0.7);
        sev.put(Severity.HIGH, 0.9); sev.put(Severity.CRITICAL, 1.0);
        return new ScoringConfig(w, 0.15, Duration.ofDays(180), Set.of(labelFactor(LabelCategory.SANCTIONED)), sev, "oci-risk-1");
    }
}
