package io.openchain.intelligence.detection;

import io.openchain.core.detection.Signal;
import io.openchain.intelligence.detection.detectors.CircularFlowDetector;
import io.openchain.intelligence.detection.detectors.DormantReactivationDetector;
import io.openchain.intelligence.detection.detectors.ExposureDetector;
import io.openchain.intelligence.detection.detectors.FanOutDetector;
import io.openchain.intelligence.detection.detectors.FastForwardingDetector;
import io.openchain.intelligence.detection.detectors.OwnershipChangeDetector;
import io.openchain.intelligence.detection.detectors.RapidDrainDetector;
import io.openchain.intelligence.detection.detectors.StructuringDetector;
import io.openchain.intelligence.detection.detectors.UnusualAmountDetector;
import io.openchain.intelligence.detection.detectors.UnusualApprovalDetector;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs every detector on a context, isolates detector failures and deduplicates signals by deterministic id. */
public final class DetectionEngine {
    private final List<Detector> detectors;
    private final SignalRepository repository;

    public DetectionEngine(List<Detector> detectors, SignalRepository repository) {
        this.detectors = List.copyOf(detectors);
        this.repository = repository;
    }

    public static List<Detector> defaultDetectors() {
        return List.of(new UnusualAmountDetector(), new StructuringDetector(), new FastForwardingDetector(),
                new DormantReactivationDetector(), new RapidDrainDetector(), new FanOutDetector(),
                new CircularFlowDetector(), new ExposureDetector(), new UnusualApprovalDetector(), new OwnershipChangeDetector());
    }

    public static DetectionEngine withDefaultDetectors(SignalRepository repository) {
        return new DetectionEngine(defaultDetectors(), repository);
    }

    public List<Detector> detectors() { return detectors; }

    public DetectionResult run(DetectionContext ctx) {
        List<Signal> fresh = new ArrayList<>();
        Map<String, String> errors = new LinkedHashMap<>();
        int dup = 0;
        for (Detector d : detectors) {
            List<Signal> out;
            try { out = d.detect(ctx); }
            catch (RuntimeException e) { errors.put(d.qualifiedId(), e.getClass().getSimpleName() + ": " + e.getMessage()); continue; }
            for (Signal s : out) {
                if (repository.saveIfAbsent(s)) fresh.add(s); else dup++;
            }
        }
        return new DetectionResult(fresh, dup, errors);
    }
}
