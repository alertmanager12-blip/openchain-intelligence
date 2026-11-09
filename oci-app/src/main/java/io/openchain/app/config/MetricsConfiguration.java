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
            }
            FunctionCounter.builder("oci.events.indexed", chains, ChainIngestionService::eventsIndexed).register(registry);
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
