package io.openchain.alerting.engine;

import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.model.Network;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A rule trigger produced by the engine: explainable evidence of WHY the rule fired. A match describes observed
 * on-chain behaviour; it is never a statement about the identity or intent of an address owner.
 *
 * @param eventTime       event time of the trigger (end of the window for aggregations)
 * @param matchedEventIds events that contributed (bounded sample, most recent last)
 * @param evidence        immutable snapshot: function, value, threshold, window bounds, leaf outcomes...
 * @param originChain     rule ids that produced the triggering event (non-empty only for internal events)
 * @param hop             hop count of the triggering event (0 for on-chain events)
 */
public record RuleMatch(String ruleId, String ruleVersion, String ruleName, Severity severity,
                        EvidenceLevel evidenceLevel, Network network, String groupKey, String dedupKey,
                        String triggeringEventId, Instant eventTime, List<String> matchedEventIds,
                        Map<String, Object> evidence, List<String> originChain, int hop) {
    public RuleMatch {
        matchedEventIds = List.copyOf(matchedEventIds);
        evidence = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(evidence));
        originChain = List.copyOf(originChain);
    }
}
