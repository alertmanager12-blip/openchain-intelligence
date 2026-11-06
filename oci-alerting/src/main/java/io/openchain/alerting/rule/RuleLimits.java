package io.openchain.alerting.rule;

import java.time.Duration;

/**
 * Hard limits enforced at validation time (and by the engine at runtime) so that a rule cannot exhaust CPU or memory.
 *
 * @param maxGroupKeysPerRule bounded window state per rule; least-recently-used keys are evicted (and counted)
 * @param maxRegexSteps       backtracking budget of a single MATCHES evaluation (character reads)
 */
public record RuleLimits(
        int maxSourceBytes,
        int maxDepth,
        int maxConditions,
        int maxListValues,
        int maxStringLength,
        int maxRegexLength,
        int maxRegexInputLength,
        long maxRegexSteps,
        Duration maxWindow,
        Duration minWindow,
        int maxGroupByFields,
        int maxGroupKeysPerRule,
        int maxSequenceSteps,
        int maxActions,
        int maxParams,
        Duration maxCooldown,
        int maxExamples,
        int maxTests,
        int maxTestEvents) {

    public static RuleLimits defaults() {
        return new RuleLimits(256 * 1024, 5, 50, 1000, 1024, 256, 1024, 100_000,
                Duration.ofDays(7), Duration.ofSeconds(1), 3, 10_000, 5, 10, 20, Duration.ofDays(7), 50, 50, 10_000);
    }

    public RuleLimits withMaxGroupKeysPerRule(int n) {
        return new RuleLimits(maxSourceBytes, maxDepth, maxConditions, maxListValues, maxStringLength, maxRegexLength,
                maxRegexInputLength, maxRegexSteps, maxWindow, minWindow, maxGroupByFields, n, maxSequenceSteps,
                maxActions, maxParams, maxCooldown, maxExamples, maxTests, maxTestEvents);
    }
}
