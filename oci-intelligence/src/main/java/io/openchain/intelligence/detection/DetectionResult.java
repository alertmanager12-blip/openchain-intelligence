package io.openchain.intelligence.detection;

import io.openchain.core.detection.Signal;
import java.util.List;
import java.util.Map;

/**
 * @param newSignals            signals not seen before (persisted by this run)
 * @param duplicatesSuppressed  signals dropped because their deterministic id already existed
 * @param detectorErrors        detectorQualifiedId -> error message; a failing detector never stops the others
 */
public record DetectionResult(List<Signal> newSignals, int duplicatesSuppressed, Map<String, String> detectorErrors) {
    public DetectionResult { newSignals = List.copyOf(newSignals); detectorErrors = Map.copyOf(detectorErrors); }
}
