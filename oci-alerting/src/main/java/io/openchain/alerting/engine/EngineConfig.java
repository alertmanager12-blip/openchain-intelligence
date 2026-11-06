package io.openchain.alerting.engine;

import java.time.Clock;
import java.time.Duration;

/**
 * Engine tuning. All memory is bounded by these values.
 *
 * @param allowedLateness   events older than (max event time seen on the network - lateness) are dropped and counted
 * @param dedupCapacity     max event ids remembered for duplicate suppression (LRU)
 * @param dedupTtl          how long (wall clock) an event id is remembered
 * @param maxGroupKeysPerRule window/sequence keys kept per rule; least recently used keys are evicted and counted
 * @param maxEntriesPerKey  events kept per (rule, key) window; oldest dropped first and counted
 * @param maxInternalHops   internal events (produced by INTERNAL_EVENT actions) with a higher hop count are ignored
 * @param maxEvidenceEvents matched event ids kept in a match's evidence
 * @param sweepEveryEvents  run a full expired-state sweep every N processed events (0 = never automatically)
 */
public record EngineConfig(Duration allowedLateness, int dedupCapacity, Duration dedupTtl, int maxGroupKeysPerRule,
                           int maxEntriesPerKey, int maxInternalHops, int maxEvidenceEvents, int sweepEveryEvents,
                           Clock clock) {

    public EngineConfig {
        if (allowedLateness.isNegative()) throw new IllegalArgumentException("allowedLateness < 0");
        if (dedupCapacity < 1 || maxGroupKeysPerRule < 1 || maxEntriesPerKey < 1 || maxEvidenceEvents < 1)
            throw new IllegalArgumentException("capacities must be >= 1");
    }

    public static EngineConfig defaults() {
        return new EngineConfig(Duration.ofMinutes(2), 200_000, Duration.ofHours(24), 10_000, 10_000, 1, 50, 10_000,
                Clock.systemUTC());
    }

    public EngineConfig withAllowedLateness(Duration d) {
        return new EngineConfig(d, dedupCapacity, dedupTtl, maxGroupKeysPerRule, maxEntriesPerKey, maxInternalHops,
                maxEvidenceEvents, sweepEveryEvents, clock);
    }

    public EngineConfig withMaxGroupKeysPerRule(int n) {
        return new EngineConfig(allowedLateness, dedupCapacity, dedupTtl, n, maxEntriesPerKey, maxInternalHops,
                maxEvidenceEvents, sweepEveryEvents, clock);
    }

    public EngineConfig withMaxEntriesPerKey(int n) {
        return new EngineConfig(allowedLateness, dedupCapacity, dedupTtl, maxGroupKeysPerRule, n, maxInternalHops,
                maxEvidenceEvents, sweepEveryEvents, clock);
    }

    public EngineConfig withDedup(int capacity, Duration ttl) {
        return new EngineConfig(allowedLateness, capacity, ttl, maxGroupKeysPerRule, maxEntriesPerKey, maxInternalHops,
                maxEvidenceEvents, sweepEveryEvents, clock);
    }

    public EngineConfig withClock(Clock c) {
        return new EngineConfig(allowedLateness, dedupCapacity, dedupTtl, maxGroupKeysPerRule, maxEntriesPerKey,
                maxInternalHops, maxEvidenceEvents, sweepEveryEvents, c);
    }
}
