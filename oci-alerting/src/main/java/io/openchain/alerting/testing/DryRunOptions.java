package io.openchain.alerting.testing;

import io.openchain.alerting.engine.EngineConfig;

/**
 * @param maxMatchRatio   warn when more than this fraction of in-scope events pass the filter (default 0.2)
 * @param maxAlertsPerDay warn when the estimated alert volume exceeds this (default 100)
 * @param sampleSize      matched event ids returned as a sample (default 20)
 */
public record DryRunOptions(EngineConfig engine, double maxMatchRatio, double maxAlertsPerDay, int sampleSize) {
    public static DryRunOptions defaults() { return new DryRunOptions(EngineConfig.defaults(), 0.2, 100, 20); }
}
