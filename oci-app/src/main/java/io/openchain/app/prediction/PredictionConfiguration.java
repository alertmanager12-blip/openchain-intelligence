package io.openchain.app.prediction;

import io.openchain.app.chain.ChainIngestionService;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Predictions (ADR-0023 amendment 2): observe the indexed flows, settle and store once a minute. */
@Configuration
public class PredictionConfiguration {
    private static final Logger log = LoggerFactory.getLogger(PredictionConfiguration.class);

    @Bean
    PredictionService predictionService(PredictionStore store, ChainIngestionService chains, Clock clock,
                                        io.openchain.intelligence.IntelligencePipeline intelligence) {
        return new PredictionService(store, n -> chains.indexingCoverage().gaps(n), clock,
                intelligence.detectionConfig().fanOut().minDistinctRecipients());
    }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService predictionTicker() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "oci-predictions");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    ApplicationRunner startPredictions(PredictionService predictions, ChainIngestionService chains, ScheduledExecutorService predictionTicker) {
        return args -> {
            predictions.start();
            chains.addFlowObserver(predictions::observe);
            predictionTicker.scheduleWithFixedDelay(() -> {
                try {
                    predictions.tick();
                } catch (RuntimeException e) {
                    log.warn("prediction tick failed: {}", e.toString());
                }
            }, 1, 1, TimeUnit.MINUTES);
        };
    }
}
