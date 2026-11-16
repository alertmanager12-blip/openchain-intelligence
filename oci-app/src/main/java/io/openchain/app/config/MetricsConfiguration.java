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

    /**
     * Working-set snapshot (ADR-0024 step 3d, design § 4.6), only when it is enabled. Durations and size are NaN until a
     * restore or a write happened; with snapshots written at shutdown only, the write values are visible in the logs of
     * the stopping application, and after a restart these metrics describe the restore.
     */
    @Bean MeterBinder workingSetSnapshotMetrics(org.springframework.beans.factory.ObjectProvider<io.openchain.app.persistence.WorkingSetSnapshotService> snapshots) {
        return registry -> {
            var s = snapshots.getIfAvailable();
            if (s == null) return;
            java.util.function.ToDoubleFunction<Long> nan = v -> v < 0 ? Double.NaN : v;
            Gauge.builder("oci.snapshot.restore.seconds", s, x -> nan.applyAsDouble(x.lastRestoreMillis()) / 1000.0)
                    .description("Duration of the last restore attempt at start-up").register(registry);
            Gauge.builder("oci.snapshot.write.seconds", s, x -> nan.applyAsDouble(x.lastWriteMillis()) / 1000.0)
                    .description("Duration of the last completed snapshot write").register(registry);
            Gauge.builder("oci.snapshot.pause.seconds", s, x -> nan.applyAsDouble(x.lastPauseMillis()) / 1000.0)
                    .description("How long indexing waited for the last periodic snapshot copy").register(registry);
            Gauge.builder("oci.snapshot.bytes", s, x -> nan.applyAsDouble(x.lastWriteBytes()))
                    .description("Size of the last completed snapshot").register(registry);
            Gauge.builder("oci.snapshot.age.seconds", s, x -> x.ageSeconds())
                    .description("Time since the snapshot last restored or written was taken").register(registry);
            for (var o : io.openchain.app.persistence.WorkingSetSnapshotService.Outcome.values()) {
                Gauge.builder("oci.snapshot.restore.outcome", s, x -> x.lastRestore() == o ? 1 : 0).tag("outcome", o.name().toLowerCase())
                        .description("1 for the outcome of the restore attempt at start-up").register(registry);
            }
            java.util.Map<String, java.util.function.ToIntFunction<io.openchain.intelligence.snapshot.WorkingSetSnapshot.Counts>> kinds =
                    java.util.Map.of("profiles", c -> c.profiles(), "flows", c -> c.flows(), "signals", c -> c.signals(),
                            "scores", c -> c.scores(), "balances", c -> c.balances());
            kinds.forEach((kind, f) -> Gauge.builder("oci.snapshot.restored.items", s, x -> {
                var c = x.lastRestored();
                return c == null ? Double.NaN : f.applyAsInt(c);
            }).tag("kind", kind).description("Items installed by the last restore").register(registry));
            FunctionCounter.builder("oci.snapshot.failures", s, x -> x.restoreFailures()).tag("phase", "restore")
                    .description("Snapshot restores or writes that failed (nothing partial is ever installed)").register(registry);
            FunctionCounter.builder("oci.snapshot.failures", s, x -> x.writeFailures()).tag("phase", "write").register(registry);
        };
    }

    /** Exempt working set, by category (step 2 point 1, observation only). No address label: the per-address detail is in
     *  {@code GET /api/v1/admin/working-set/exempt} (permission keys:admin), not in Prometheus. */
    @Bean MeterBinder exemptWorkingSetMetrics(io.openchain.app.chain.ExemptWorkingSetMonitor monitor) {
        return registry -> {
            for (var c : io.openchain.app.chain.ExemptWorkingSetMonitor.Category.values()) {
                String tag = c.name().toLowerCase();
                java.util.function.ToDoubleFunction<java.util.function.ToLongFunction<io.openchain.app.chain.ExemptWorkingSetMonitor.CategorySummary>> of =
                        f -> { var s = monitor.last(); return s == null ? Double.NaN : f.applyAsLong(s.byCategory().get(c)); };
                Gauge.builder("oci.exempt.addresses", monitor, m -> of.applyAsDouble(x -> x.addresses())).tag("category", tag)
                        .description("Addresses exempt from the working-set trim").register(registry);
                Gauge.builder("oci.exempt.flows", monitor, m -> of.applyAsDouble(x -> x.flows())).tag("category", tag)
                        .description("Flows in memory touching exempt addresses (a flow between two of them counted for each)").register(registry);
                Gauge.builder("oci.exempt.signals", monitor, m -> of.applyAsDouble(x -> x.signals())).tag("category", tag).register(registry);
                Gauge.builder("oci.exempt.memory.estimate.bytes", monitor, m -> of.applyAsDouble(x -> x.estimatedBytes())).tag("category", tag)
                        .description("Estimate: flows x 703 B + signals x 1,057 B (lab costs)").register(registry);
                Gauge.builder("oci.exempt.max.flows.per.address", monitor, m -> of.applyAsDouble(x -> x.maxFlowsPerAddress())).tag("category", tag).register(registry);
                Gauge.builder("oci.exempt.oldest.view.age.seconds", monitor, m -> {
                    var s = monitor.last();
                    Long v = s == null ? null : s.byCategory().get(c).oldestViewAgeSeconds();
                    return v == null ? Double.NaN : v;
                }).tag("category", tag).description("Oldest last consultation among the category's addresses consulted since start-up").register(registry);
                Gauge.builder("oci.exempt.not.viewed.since.start", monitor, m -> of.applyAsDouble(x -> x.notViewedSinceStart())).tag("category", tag).register(registry);
            }
            Gauge.builder("oci.exempt.flows.distinct", monitor, m -> { var s = m.last(); return s == null ? Double.NaN : s.distinctExemptFlows(); })
                    .description("Distinct flows touching at least one exempt address").register(registry);
            Gauge.builder("oci.exempt.proposed.cap.addresses.over", monitor, m -> { var s = m.last(); return s == null ? Double.NaN : s.addressesOverProposedPerAddressCap(); })
                    .description("Information only: addresses above the proposed 10,000 flows per address (not enforced)").register(registry);
            Gauge.builder("oci.exempt.proposed.cap.flows.over", monitor, m -> { var s = m.last(); return s == null ? Double.NaN : s.overProposedTotal(); })
                    .description("Information only: exempt flows outside the watchlist above the proposed 150,000 (not enforced)").register(registry);
        };
    }

    /** Instance sharing of stored flows (step 3 option A): size of the bounded token table and how often it shares. */
    @Bean MeterBinder canonicalInstanceMetrics(io.openchain.intelligence.IntelligencePipeline intelligence) {
        return registry -> {
            var c = intelligence.canonicalInstances();
            Gauge.builder("oci.canonical.tokens", c, x -> x.stats().tokens()).description("Canonical token instances held (bounded)").register(registry);
            Gauge.builder("oci.canonical.token.lookups", c, x -> x.stats().tokenHits()).tag("result", "shared").register(registry);
            Gauge.builder("oci.canonical.token.lookups", c, x -> x.stats().tokenMisses()).tag("result", "registered").register(registry);
            Gauge.builder("oci.canonical.addresses.shared", c, x -> x.stats().addressesShared())
                    .description("Flow addresses replaced by the instance held by the wallet profile").register(registry);
        };
    }

    /** Rule evaluations whose transaction failed, retried, or dropped (code review I5). */
    @Bean MeterBinder evaluationRetryMetrics(ChainIngestionService chains) {
        return registry -> {
            FunctionCounter.builder("oci.alerting.evaluation.failed", chains, c -> c.evaluationFailures())
                    .description("Rule evaluations whose transaction failed (engine state undone, event queued)").register(registry);
            FunctionCounter.builder("oci.alerting.evaluation.retried", chains, c -> c.evaluationsRetried())
                    .description("Queued rule evaluations that succeeded on retry").register(registry);
            FunctionCounter.builder("oci.alerting.evaluation.dropped", chains, c -> c.evaluationsDroppedLate()).tag("reason", "late")
                    .description("Queued rule evaluations given up (alerts possibly lost)").register(registry);
            FunctionCounter.builder("oci.alerting.evaluation.dropped", chains, c -> c.evaluationsDroppedQueueFull()).tag("reason", "queue_full")
                    .register(registry);
            Gauge.builder("oci.alerting.evaluation.pending", chains, c -> c.pendingEvaluations())
                    .description("Rule evaluations waiting for retry").register(registry);
        };
    }

    /** Aggregation alerts re-evaluated after a reorganisation, by verdict (code review I3). */
    @Bean MeterBinder alertThresholdRecheckMetrics(io.openchain.alerting.alert.AlertService alerts) {
        return registry -> {
            FunctionCounter.builder("oci.alert.threshold.recheck", alerts, a -> a.thresholdStillMet()).tag("verdict", "still_met")
                    .description("Aggregation alerts whose threshold was re-evaluated after a retraction").register(registry);
            FunctionCounter.builder("oci.alert.threshold.recheck", alerts, a -> a.thresholdNoLongerMet()).tag("verdict", "no_longer_met").register(registry);
            FunctionCounter.builder("oci.alert.threshold.recheck", alerts, a -> a.thresholdUnverifiable()).tag("verdict", "unverifiable").register(registry);
        };
    }

    @Bean MeterBinder ociMetrics(ChainIngestionService chains, DeliveryService delivery, RuleEngine engine, RuleRegistry rules,
                                 io.openchain.intelligence.IntelligencePipeline intelligence) {
        return registry -> {
            for (Network n : Network.values()) {
                String id = n.id();
                Gauge.builder("oci.indexer.lag.blocks", chains, c -> c.runtime(n).map(rt -> {
                            long indexed = rt.lastIndexedBlock();
                            var lag = ChainIngestionService.indexerLag(rt.lastStatus(), indexed);
                            return indexed < 0 || lag.isEmpty() ? Double.NaN : (double) lag.getAsLong();
                        }).orElse(Double.NaN))
                        .description("Blocks between the chain head and the last indexed block; NaN when the head is unknown (provider failing)").tag("network", id).register(registry);
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
            // Fund Flow Trace P0-B (C7): one transfer seen live and by an address history without log index counts once
            FunctionCounter.builder("oci.history.duplicates", chains, ChainIngestionService::historyDuplicatesSkipped).tag("action", "skipped")
                    .description("History transfers not applied: already indexed live").register(registry);
            FunctionCounter.builder("oci.history.duplicates", chains, ChainIngestionService::historyDuplicatesReplaced).tag("action", "replaced")
                    .description("History transfers replaced by the same transfer indexed live").register(registry);
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
            // seen-address filter (age of evicted wallets, code review B3): fill and estimated error rate
            Gauge.builder("oci.seen.addresses.fill.ratio", chains, c -> c.seenAddressesStats()[0])
                    .description("Share of bits set in the seen-address filter; above 0.5 raise OCI_INGESTION_SEEN_ADDRESSES_BITS").register(registry);
            Gauge.builder("oci.seen.addresses.false.positive.rate", chains, c -> c.seenAddressesStats()[1])
                    .description("Estimated share of new addresses reported as already seen (their age is then withheld)").register(registry);
            Gauge.builder("oci.seen.addresses", chains, c -> c.seenAddressesStats()[2])
                    .description("Distinct addresses recorded since start-up").register(registry);
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
