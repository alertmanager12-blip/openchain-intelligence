package io.openchain.core.detection;

import io.openchain.core.model.Address;
import java.time.Instant;
import java.util.List;

/**
 * Output of the Detection Engine. A signal is NEVER an accusation: {@link EvidenceLevel} makes explicit
 * whether it is a statistical anomaly, a risk indicator, or confirmed by an external authoritative source.
 */
public record Signal(
        String id,
        SignalType type,
        Address address,
        String txHash,           // nullable
        Severity severity,
        double confidence,       // 0..1
        EvidenceLevel evidenceLevel,
        List<Evidence> evidence,
        String detectorId,       // rule/detector that produced it, with version, e.g. "fast-forward@1"
        Instant detectedAt) {

    public Signal {
        if (confidence < 0 || confidence > 1) throw new IllegalArgumentException("confidence must be in [0,1]");
        evidence = List.copyOf(evidence);
    }

    public enum EvidenceLevel {
        /** Deviation from baseline; no implication of wrongdoing. */
        ANOMALY,
        /** Pattern or exposure associated with risk. */
        RISK_SIGNAL,
        /** Backed by an authoritative source (e.g. official sanctions list). Still not a criminal qualification. */
        CONFIRMED_BY_SOURCE
    }

    public record Evidence(String kind, String description, String reference, String source, Instant observedAt) {}
}
