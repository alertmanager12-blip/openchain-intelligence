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

    /**
     * AI summary providers from the settings (ADR-0022 amendment 5). A provider with a missing key, model or URL is left out
     * with an error in the log: a misconfiguration never sends data to an unintended place.
     */
    @Bean
    InvestigationSummaryService investigationSummaryService(TraceArtifactStore store, InvestigationSummaryStore summaries, Clock clock,
                                                            io.openchain.app.config.OciProperties props) {
        var cfg = props.getAiSummary();
        java.util.List<InvestigationSummaryService.Provider> providers = new java.util.ArrayList<>();
        if (cfg.isEnabled()) {
            for (var p : cfg.getProviders()) {
                try {
                    providers.add(provider(p, cfg.getTimeout()));
                } catch (IllegalArgumentException e) {
                    log.error("AI summary provider '{}' left out: {}", p.getId(), e.getMessage());
                }
            }
            log.info("AI summaries enabled with {} provider(s): {}", providers.size(), providers.stream().map(InvestigationSummaryService.Provider::id).toList());
        }
        return new InvestigationSummaryService(store, summaries, providers, cfg.isEnabled(), cfg.getDailyCap(), clock);
    }

    static InvestigationSummaryService.Provider provider(io.openchain.app.config.OciProperties.AiSummary.Provider p, java.time.Duration timeout) {
        if (p.getId() == null || !p.getId().matches("[a-z0-9][a-z0-9-]{0,31}")) throw new IllegalArgumentException("id must be a short lowercase slug");
        if (p.getModel() == null || p.getModel().isBlank()) throw new IllegalArgumentException("model is required");
        String connector = p.getConnector() == null ? "" : p.getConnector().trim().toLowerCase(java.util.Locale.ROOT);
        io.openchain.app.summary.SummaryWriter w = switch (connector) {
            case "claude" -> {
                if (p.getApiKey() == null || p.getApiKey().isBlank()) throw new IllegalArgumentException("api key is required");
                yield new io.openchain.app.summary.AnthropicSummaryWriter(p.getApiKey(), p.getBaseUrl(), p.getModel(), timeout);
            }
            case "openai-compatible" -> new io.openchain.app.summary.OpenAiCompatibleSummaryWriter(p.getBaseUrl(), p.getApiKey(), p.getModel(),
                    "json_object".equalsIgnoreCase(p.getResponseFormat()) ? io.openchain.app.summary.OpenAiCompatibleSummaryWriter.ResponseFormat.JSON_OBJECT
                            : io.openchain.app.summary.OpenAiCompatibleSummaryWriter.ResponseFormat.JSON_SCHEMA, timeout);
            default -> throw new IllegalArgumentException("connector must be claude or openai-compatible");
        };
        String label = p.getLabel() == null || p.getLabel().isBlank() ? p.getId() : p.getLabel();
        return new InvestigationSummaryService.Provider(p.getId(), label, connector, p.getModel(), p.getDataLocation(), w);
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
