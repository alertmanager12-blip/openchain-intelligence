package io.openchain.alerting.engine;

import io.openchain.alerting.rule.compile.CompiledCondition;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Engine counters. Exposed as a plain snapshot map so oci-app can bridge them to Micrometer without coupling. */
public final class EngineMetrics implements CompiledCondition.EvalStats {
    final AtomicLong eventsReceived = new AtomicLong();
    final AtomicLong eventsProcessed = new AtomicLong();
    final AtomicLong duplicatesIgnored = new AtomicLong();
    final AtomicLong lateAccepted = new AtomicLong();
    final AtomicLong lateDropped = new AtomicLong();
    final AtomicLong retractions = new AtomicLong();
    final AtomicLong retractionsIgnored = new AtomicLong();
    final AtomicLong loopSuppressed = new AtomicLong();
    final AtomicLong invalidEvents = new AtomicLong();
    final AtomicLong missingGroupKey = new AtomicLong();
    final AtomicLong missingAggregateValue = new AtomicLong();
    final AtomicLong groupKeysEvicted = new AtomicLong();
    final AtomicLong windowEntriesDropped = new AtomicLong();
    final AtomicLong regexBudgetExceeded = new AtomicLong();
    final AtomicLong typeMismatches = new AtomicLong();
    final AtomicLong triggers = new AtomicLong();

    @Override public void regexBudgetExceeded() { regexBudgetExceeded.incrementAndGet(); }
    @Override public void typeMismatch() { typeMismatches.incrementAndGet(); }

    public long duplicatesIgnored() { return duplicatesIgnored.get(); }
    public long lateAccepted() { return lateAccepted.get(); }
    public long lateDropped() { return lateDropped.get(); }
    public long retractions() { return retractions.get(); }
    public long loopSuppressed() { return loopSuppressed.get(); }
    public long groupKeysEvicted() { return groupKeysEvicted.get(); }
    public long windowEntriesDropped() { return windowEntriesDropped.get(); }
    public long regexBudgetExceededCount() { return regexBudgetExceeded.get(); }
    public long typeMismatches() { return typeMismatches.get(); }
    public long triggers() { return triggers.get(); }

    /** Metric name -> value, names prefixed {@code oci.alerting.engine.}. */
    public Map<String, Long> snapshot() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("events.received", eventsReceived.get());
        m.put("events.processed", eventsProcessed.get());
        m.put("events.duplicates", duplicatesIgnored.get());
        m.put("events.late.accepted", lateAccepted.get());
        m.put("events.late.dropped", lateDropped.get());
        m.put("events.retractions", retractions.get());
        m.put("events.retractions.ignored", retractionsIgnored.get());
        m.put("events.loop.suppressed", loopSuppressed.get());
        m.put("events.invalid", invalidEvents.get());
        m.put("groupkey.missing", missingGroupKey.get());
        m.put("aggregate.value.missing", missingAggregateValue.get());
        m.put("groupkey.evicted", groupKeysEvicted.get());
        m.put("window.entries.dropped", windowEntriesDropped.get());
        m.put("regex.budget.exceeded", regexBudgetExceeded.get());
        m.put("type.mismatch", typeMismatches.get());
        m.put("triggers", triggers.get());
        Map<String, Long> prefixed = new LinkedHashMap<>();
        m.forEach((k, v) -> prefixed.put("oci.alerting.engine." + k, v));
        return prefixed;
    }
}
