package io.openchain.app.config;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.openchain.alerting.engine.RuleEngine;
import io.openchain.alerting.notify.delivery.DeliveryService;
import io.openchain.app.chain.ChainIngestionService;
import io.openchain.app.rules.RuleRegistry;
import io.openchain.core.model.Network;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Prometheus metrics (/actuator/prometheus): indexing, balance lookups, rule engine and notification delivery. Values are
 * read from the services' own counters at scrape time (no double bookkeeping on hot paths).
 */
@Configuration(proxyBeanMethods = false)
public class MetricsConfiguration {

    @Bean MeterBinder journalMetrics(org.springframework.beans.factory.ObjectProvider<io.openchain.app.journal.JdbcIntelligenceJournal> journals) {
        return registry -> {
            var j = journals.getIfAvailable();
            if (j == null) return;
            FunctionCounter.builder("oci.journal.written", j, x -> x.written()).description("Records written to the intelligence journal").register(registry);
            FunctionCounter.builder("oci.journal.dropped", j, x -> x.dropped()).description("Records dropped (journal full or write error)").register(registry);
            Gauge.builder("oci.journal.backlog", j, x -> x.backlog()).description("Records waiting to be written").register(registry);
        };
    }

    @Bean MeterBinder ociMetrics(ChainIngestionService chains, DeliveryService delivery, RuleEngine engine, RuleRegistry rules,
                                 io.openchain.intelligence.IntelligencePipeline intelligence) {
        return registry -> {
            for (Network n : Network.values()) {
                String id = n.id();
                Gauge.builder("oci.indexer.lag.blocks", chains, c -> c.runtime(n).map(rt -> {
                            var st = rt.lastStatus();
                            long indexed = rt.lastIndexedBlock();
                            return st == null || indexed < 0 ? Double.NaN : (double) Math.max(0, st.headBlock() - indexed);
                        }).orElse(Double.NaN))
                        .description("Blocks between the chain head and the last indexed block").tag("network", id).register(registry);
                Gauge.builder("oci.indexer.last.block", chains, c -> c.runtime(n).map(rt -> (double) rt.lastIndexedBlock()).orElse(Double.NaN))
                        .tag("network", id).register(registry);
                FunctionCounter.builder("oci.reorg", chains, c -> c.reorgs(n)).description("Chain reorganisations handled")
                        .tag("network", id).register(registry);
                FunctionCounter.builder("oci.indexer.poll.seconds", chains, c -> c.pollSeconds(n))
                        .description("Time spent in indexer passes (fetch and processing)").tag("network", id).register(registry);
                FunctionCounter.builder("oci.process.seconds", chains, c -> c.processSeconds(n))
                        .description("Time spent processing events under the processing lock").tag("network", id).register(registry);
                FunctionCounter.builder("oci.process.lock.wait.seconds", chains, c -> c.lockWaitSeconds(n))
                        .description("Time events waited for the processing lock").tag("network", id).register(registry);
            }
            FunctionCounter.builder("oci.events.indexed", chains, ChainIngestionService::eventsIndexed).register(registry);
            // working set (ADR-0024 step 3a): sizes after the last trim, and its duration
            for (var e : java.util.Map.<String, java.util.function.ToDoubleFunction<ChainIngestionService.TrimResult>>of(
                    "flows", t -> t.remainingFlows(), "signals", t -> t.remainingSignals(), "profiles", t -> t.remainingProfiles(),
                    "scored_addresses", t -> t.remainingScoredAddresses(), "tracked_wallets", t -> t.trackedWallets()).entrySet()) {
                Gauge.builder("oci.working.set.size", chains, c -> c.lastTrim() == null ? Double.NaN : e.getValue().applyAsDouble(c.lastTrim()))
                        .description("Items held in memory after the last working-set trim").tag("kind", e.getKey()).register(registry);
            }
            Gauge.builder("oci.working.set.trim.millis", chains, c -> c.lastTrim() == null ? Double.NaN : c.lastTrim().millis())
                    .description("Duration of the last working-set trim").register(registry);
            FunctionCounter.builder("oci.events.retracted", chains, ChainIngestionService::eventsRetracted).register(registry);
            // signal removals (P0 item 3): nothing is removed silently
            var signalStore = intelligence.signals();
            for (var reason : io.openchain.intelligence.detection.SignalRepository.EvictionReason.values()) {
                for (var sev : io.openchain.core.detection.Severity.values()) {
                    String prefix = reason + "|" + sev + "|";
                    FunctionCounter.builder("oci.signals.evicted", signalStore, st -> st.evictionCounts().entrySet().stream()
                                    .filter(e -> e.getKey().startsWith(prefix)).mapToLong(java.util.Map.Entry::getValue).sum())
                            .description("Signals removed from the working set").tag("reason", reason.name().toLowerCase())
                            .tag("severity", sev.name()).register(registry);
                }
                for (var type : io.openchain.core.detection.SignalType.values()) {
                    String prefix = reason + "|", suffix = "|" + type;
                    FunctionCounter.builder("oci.signals.evicted.by.type", signalStore, st -> st.evictionCounts().entrySet().stream()
                                    .filter(e -> e.getKey().startsWith(prefix) && e.getKey().endsWith(suffix)).mapToLong(java.util.Map.Entry::getValue).sum())
                            .description("Signals removed from the working set, by signal type").tag("reason", reason.name().toLowerCase())
                            .tag("type", type.name()).register(registry);
                }
            }
            // chain reorganisations (docs/P0_REORG_DESIGN.md)
            FunctionCounter.builder("oci.reorg.reverted.flows", chains, ChainIngestionService::reorgRevertedFlows)
                    .description("Value flows reverted after their block left the canonical chain").register(registry);
            FunctionCounter.builder("oci.reorg.signals", chains, ChainIngestionService::reorgSignalsRemoved).tag("outcome", "withdrawn").register(registry);
            FunctionCounter.builder("oci.reorg.signals", chains, ChainIngestionService::reorgSignalsRecomputed).tag("outcome", "recomputed").register(registry);
            FunctionCounter.builder("oci.reorg.signals", chains, ChainIngestionService::reorgSignalsAdded).tag("outcome", "added").register(registry);
            FunctionCounter.builder("oci.reorg.ignored", chains, ChainIngestionService::reorgIgnored)
                    .description("Retractions with nothing to revert (unknown or already retracted: idempotent)").register(registry);
            FunctionCounter.builder("oci.reorg.inexact.profiles", chains, ChainIngestionService::reorgInexactProfiles)
                    .description("Retracted flows older than the exact memory of a profile (totals of that side left unchanged)").register(registry);
            FunctionCounter.builder("oci.reorg.replay.truncated", chains, ChainIngestionService::reorgReplayTruncated)
                    .description("Re-evaluations that hit the trigger budget").register(registry);
            FunctionCounter.builder("oci.balance.lookups", chains, ChainIngestionService::balanceLookups).tag("outcome", "made").register(registry);
            FunctionCounter.builder("oci.balance.lookups", chains, ChainIngestionService::balanceLookupFailures).tag("outcome", "failed").register(registry);
            FunctionCounter.builder("oci.balance.lookups", chains, ChainIngestionService::balanceLookupsSkipped).tag("outcome", "skipped").register(registry);
            FunctionCounter.builder("oci.deliveries", delivery, DeliveryService::deliveredCount).tag("status", "delivered").register(registry);
            FunctionCounter.builder("oci.deliveries", delivery, DeliveryService::deadLetteredCount).tag("status", "dead_letter").register(registry);
            FunctionCounter.builder("oci.deliveries", delivery, DeliveryService::skippedCount).tag("status", "skipped").register(registry);
            FunctionCounter.builder("oci.rule.triggers", engine, e -> e.metrics().triggers()).register(registry);
            FunctionCounter.builder("oci.rule.late.events", engine, e -> e.metrics().lateDropped()).register(registry);
            Gauge.builder("oci.rules.enabled", rules, r -> r.list().stream().filter(RuleRegistry.StoredRule::enabled).count())
                    .register(registry);
        };
    }
}
