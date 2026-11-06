package io.openchain.alerting.testing;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Result of replaying historical events through one rule with NO notifications.
 *
 * @param conditionMatches       in-scope events passing the stateless filter
 * @param triggers               raw engine triggers
 * @param estimatedAlerts        alerts after dedup/cooldown grouping (what would actually be notified)
 * @param estimatedTriggersPerDay raw triggers extrapolated to 24h of event time
 * @param estimatedAlertsPerDay  alerts extrapolated to 24h of event time
 */
public record DryRunReport(String ruleRef, int totalEvents, int eventsInScope, int conditionMatches, int triggers,
                           int estimatedAlerts, List<String> matchedEventIdsSample, Duration observedSpan,
                           double estimatedTriggersPerDay, double estimatedAlertsPerDay, List<Warning> warnings,
                           Map<String, Long> engineMetrics) {

    public record Warning(String code, String message) {}

    public DryRunReport {
        matchedEventIdsSample = List.copyOf(matchedEventIdsSample);
        warnings = List.copyOf(warnings);
        engineMetrics = Map.copyOf(engineMetrics);
    }

    public boolean permissive() {
        return warnings.stream().anyMatch(w -> w.code().equals("PERMISSIVE_MATCH_RATIO") || w.code().equals("NO_NUMERIC_THRESHOLD")
                || w.code().equals("HIGH_ALERT_VOLUME"));
    }
}
