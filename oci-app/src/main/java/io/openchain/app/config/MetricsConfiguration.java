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

    @Bean MeterBinder ociMetrics(ChainIngestionService chains, DeliveryService delivery, RuleEngine engine, RuleRegistry rules) {
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
