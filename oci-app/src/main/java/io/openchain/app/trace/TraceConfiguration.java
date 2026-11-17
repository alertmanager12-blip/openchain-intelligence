package io.openchain.app.trace;

import io.openchain.app.analysis.InvestigationService;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Stored traces (ADR-0022): one worker thread and a bounded queue, off the ingestion path; unfinished runs resume at start. */
@Configuration(proxyBeanMethods = false)
public class TraceConfiguration {
    private static final Logger log = LoggerFactory.getLogger(TraceConfiguration.class);
    static final int QUEUE = 50;

    @Bean(destroyMethod = "shutdownNow")
    ExecutorService traceExecutor() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(QUEUE), r -> {
            Thread t = new Thread(r, "oci-trace");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    TraceArtifactService traceArtifactService(TraceArtifactStore store, InvestigationService investigation, Clock clock, ExecutorService traceExecutor,
                                              io.openchain.intelligence.IntelligencePipeline intelligence) {
        TraceArtifactService s = new TraceArtifactService(store, investigation::traceSource, clock, traceExecutor, TraceArtifactService.Budget.DEFAULTS);
        // inputs of the investigation plan, taken when it is requested and stored with it (replayable)
        s.setInvestigationInputs(a -> {
            var c = investigation.addressCoverage(a);
            var exposure = intelligence.detectionConfig().exposure();
            return new io.openchain.intelligence.investigation.InvestigationPlan.Inputs(a,
                    new io.openchain.intelligence.investigation.InvestigationPlan.Coverage(c.state().name(), c.fromBlock(), c.toBlock(), c.reasons()),
                    intelligence.signals().findByAddress(a), exposure,
                    io.openchain.intelligence.relations.WalletRelations.Config.defaults(exposure.dustThresholds()),
                    new io.openchain.intelligence.trace.Trace.Limits(3, 500, 20_000));
        });
        return s;
    }

    @Bean
    AssertionReviewService assertionReviewService(TraceArtifactStore store, AssertionReviewStore reviews, Clock clock,
                                                  io.openchain.app.config.OciProperties props) {
        return new AssertionReviewService(store, reviews, clock, () -> props.getInvestigation().isFourEyes());
    }

    @Bean
    RuleProposalService ruleProposalService(AssertionReviewService reviews, io.openchain.app.rules.RuleRegistry registry,
                                            io.openchain.alerting.testing.RuleTester tester, io.openchain.app.chain.EventHistory history, Clock clock,
                                            io.openchain.app.config.OciProperties props) {
        return new RuleProposalService(reviews, registry, tester, history, clock, props.getRules().getMaxDryRunEvents());
    }

    /** A transfer retracted by a reorganisation makes STALE every stored trace that froze it (all client scopes). */
    @Bean
    ApplicationRunner staleOnReorg(io.openchain.app.chain.ChainIngestionService chains, TraceArtifactStore store) {
        return args -> chains.onFlowRetracted(id -> {
            var hit = store.markStale(java.util.Set.of(id));
            if (!hit.isEmpty()) log.info("{} stored trace(s) marked stale: transfer {} left the canonical chain", hit.size(), id);
        });
    }

    @Bean
    ApplicationRunner resumeTraces(TraceArtifactService traces) {
        return args -> {
            int n = traces.resumePending();
            if (n > 0) log.info("{} stored trace(s) left unfinished by a restart resumed", n);
        };
    }
}
