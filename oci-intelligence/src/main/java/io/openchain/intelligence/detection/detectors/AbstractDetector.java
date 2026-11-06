package io.openchain.intelligence.detection.detectors;

import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal;
import io.openchain.core.detection.Signal.Evidence;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.detection.SignalType;
import io.openchain.core.model.Address;
import io.openchain.intelligence.detection.DetectionContext;
import io.openchain.intelligence.detection.Detector;
import io.openchain.intelligence.detection.SignalIds;
import io.openchain.intelligence.flow.ValueFlow;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Shared helpers: deterministic ids, flow evidence, window filtering. */
public abstract class AbstractDetector implements Detector {
    private final String id;
    private final int version;

    protected AbstractDetector(String id, int version) { this.id = id; this.version = version; }

    @Override public final String id() { return id; }
    @Override public final int version() { return version; }

    protected Signal signal(DetectionContext ctx, SignalType type, Address address, String txHash, Severity severity,
                            double confidence, EvidenceLevel level, List<Evidence> evidence, String discriminator) {
        return new Signal(SignalIds.of(qualifiedId(), address, type + "|" + discriminator), type, address, txHash, severity,
                clamp(confidence), level, evidence, qualifiedId(), ctx.now());
    }

    protected static Evidence flowEvidence(ValueFlow f, String description) {
        return new Evidence("flow", description + ": " + f.from().value() + " -> " + f.to().value() + " "
                + f.amount().stripTrailingZeros().toPlainString() + " " + f.token().symbol(),
                f.txHash(), "chain:" + f.network().id(), f.timestamp());
    }

    protected static List<Evidence> flowEvidence(List<ValueFlow> flows, String description, int max) {
        List<Evidence> ev = new ArrayList<>();
        for (int i = 0; i < flows.size() && i < max; i++) ev.add(flowEvidence(flows.get(i), description));
        return ev;
    }

    /** Flows with timestamp in [from, to] (inclusive). */
    protected static List<ValueFlow> between(List<ValueFlow> flows, Instant from, Instant to) {
        return flows.stream().filter(f -> !f.timestamp().isBefore(from) && !f.timestamp().isAfter(to)).toList();
    }

    protected static List<ValueFlow> sameToken(List<ValueFlow> flows, ValueFlow ref) {
        return flows.stream().filter(f -> f.tokenKey().equals(ref.tokenKey())).toList();
    }

    protected static BigDecimal sum(List<ValueFlow> flows) {
        return flows.stream().map(ValueFlow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    protected static String plain(BigDecimal d) { return d.stripTrailingZeros().toPlainString(); }

    protected static String human(Duration d) {
        long s = d.toSeconds();
        if (s >= 86_400) return (s / 86_400) + "d";
        if (s >= 3_600) return (s / 3_600) + "h";
        if (s >= 60) return (s / 60) + "m";
        return s + "s";
    }

    protected static double clamp(double c) { return Math.max(0, Math.min(1, c)); }
}
