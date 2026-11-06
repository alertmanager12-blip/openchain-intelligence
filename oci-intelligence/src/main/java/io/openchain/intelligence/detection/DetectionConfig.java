package io.openchain.intelligence.detection;

import io.openchain.intelligence.label.LabelCategory;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** All detector thresholds. Amounts are in human token units (exact BigDecimal). Use {@link #defaults()} and {@code with*} copies. */
public record DetectionConfig(
        UnusualAmount unusualAmount,
        Structuring structuring,
        FastForwarding fastForwarding,
        Dormancy dormancy,
        RapidDrain rapidDrain,
        FanOut fanOut,
        CircularFlow circularFlow,
        Exposure exposure,
        Approval approval,
        int maxEvidenceItems) {

    /** Robust z-score = 0.6745 * (x - median) / MAD over the wallet's own history of the same token. */
    public record UnusualAmount(int minHistory, double zThreshold) {}

    /** {@code minCount} outgoing transfers in [threshold*(1-marginRatio), threshold) within {@code window}. */
    public record Structuring(Map<String, BigDecimal> thresholdsBySymbol, BigDecimal defaultThreshold,
                              double marginRatio, int minCount, Duration window) {
        public BigDecimal thresholdFor(String symbol) {
            return thresholdsBySymbol.getOrDefault(symbol == null ? "" : symbol.toUpperCase(), defaultThreshold);
        }
    }

    /** Outflow >= minRatio of inflow received within {@code window} before the outflow. */
    public record FastForwarding(double minRatio, Duration window, BigDecimal minInflow) {}

    /** Outgoing activity after at least {@code minDormancy} without any activity. */
    public record Dormancy(Duration minDormancy) {}

    /** Outflow >= minRatio of (pre-window balance estimate + window inflow) within {@code window}; requires a pre-existing balance. */
    public record RapidDrain(double minRatio, Duration window, BigDecimal minAmount) {}

    /** At least {@code minDistinctRecipients} distinct recipients within {@code window}. */
    public record FanOut(int minDistinctRecipients, Duration window) {}

    /** Value returning to the wallet through a chain of transfers, time-ordered, within {@code window}. */
    public record CircularFlow(int maxDepth, Duration window, boolean sameTokenOnly, int maxExpansions) {}

    /**
     * Exposure to labelled addresses via actual value flows. Indirect exposure is traced backwards
     * (funds that reached the wallet), time-consistent, hop-limited; confidence = reliability * hopDecay^(hops-1).
     * Propagation stops at {@code stopAt} categories (e.g. exchanges: pooled funds).
     */
    public record Exposure(int maxHops, double hopDecay, double minConfidence, int maxPaths, int maxVisitedFlows,
                           Set<LabelCategory> stopAt) {}

    /** Approvals with raw amount >= {@code unlimitedThreshold} are considered unlimited. */
    public record Approval(BigInteger unlimitedThreshold) {}

    public static DetectionConfig defaults() {
        return new DetectionConfig(
                new UnusualAmount(10, 3.5),
                new Structuring(Map.of("USDT", new BigDecimal("10000"), "USDC", new BigDecimal("10000")),
                        new BigDecimal("10000"), 0.10, 3, Duration.ofHours(24)),
                new FastForwarding(0.90, Duration.ofMinutes(30), BigDecimal.ZERO),
                new Dormancy(Duration.ofDays(180)),
                new RapidDrain(0.80, Duration.ofHours(1), BigDecimal.ZERO),
                new FanOut(20, Duration.ofHours(1)),
                new CircularFlow(4, Duration.ofDays(7), true, 10_000),
                new Exposure(3, 0.5, 0.05, 20, 5_000, Set.of(LabelCategory.EXCHANGE, LabelCategory.BRIDGE)),
                new Approval(BigInteger.TWO.pow(96).subtract(BigInteger.ONE)),
                20);
    }

    public DetectionConfig withStructuring(Structuring s) { return new DetectionConfig(unusualAmount, s, fastForwarding, dormancy, rapidDrain, fanOut, circularFlow, exposure, approval, maxEvidenceItems); }
    public DetectionConfig withFanOut(FanOut f) { return new DetectionConfig(unusualAmount, structuring, fastForwarding, dormancy, rapidDrain, f, circularFlow, exposure, approval, maxEvidenceItems); }
    public DetectionConfig withExposure(Exposure e) { return new DetectionConfig(unusualAmount, structuring, fastForwarding, dormancy, rapidDrain, fanOut, circularFlow, e, approval, maxEvidenceItems); }
    public DetectionConfig withUnusualAmount(UnusualAmount u) { return new DetectionConfig(u, structuring, fastForwarding, dormancy, rapidDrain, fanOut, circularFlow, exposure, approval, maxEvidenceItems); }
}
