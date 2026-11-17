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
    TraceArtifactService traceArtifactService(TraceArtifactStore store, InvestigationService investigation, Clock clock, ExecutorService traceExecutor) {
        return new TraceArtifactService(store, investigation::traceSource, clock, traceExecutor, TraceArtifactService.Budget.DEFAULTS);
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
