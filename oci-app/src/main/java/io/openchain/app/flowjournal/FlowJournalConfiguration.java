package io.openchain.app.flowjournal;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.openchain.app.chain.ChainIngestionService;
import io.openchain.app.watchlist.WatchlistService;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The journal of watched addresses (ADR-0026 amendment 1): observe live transfers, write every 5 s. */
@Configuration
public class FlowJournalConfiguration {
    private static final Logger log = LoggerFactory.getLogger(FlowJournalConfiguration.class);
    /** Transfers waiting to be written before new ones are dropped (and counted). */
    static final int CAPACITY = 100_000;

    @Bean
    FlowJournalWriter flowJournalWriter(FlowJournal journal, WatchlistService watchlist, Clock clock) {
        return new FlowJournalWriter(journal, watchlist::isWatched, watchlist::watched, clock, CAPACITY);
    }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService flowJournalTicker() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "oci-flow-journal");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    ApplicationRunner startFlowJournal(FlowJournalWriter writer, WatchlistService watchlist, ChainIngestionService chains,
                                       ScheduledExecutorService flowJournalTicker) {
        return args -> {
            try {
                writer.coverWatched();
            } catch (RuntimeException e) {
                log.warn("flow journal: coverage of watched addresses not recorded: {}", e.toString());
            }
            watchlist.onAdded(writer::enrolled);
            chains.addFlowObserver((flow, signals) -> writer.observe(flow));
            flowJournalTicker.scheduleWithFixedDelay(() -> {
                try {
                    writer.drain();
                } catch (RuntimeException e) {
                    log.warn("flow journal drain failed: {}", e.toString());
                }
            }, 5, 5, TimeUnit.SECONDS);
        };
    }

    @Bean
    MeterBinder flowJournalMetrics(FlowJournalWriter w) {
        return registry -> {
            FunctionCounter.builder("oci.flow_journal.written", w, FlowJournalWriter::written).description("Transfers of watched addresses written").register(registry);
            FunctionCounter.builder("oci.flow_journal.dropped", w, FlowJournalWriter::dropped).description("Transfers not written: queue full").register(registry);
            FunctionCounter.builder("oci.flow_journal.failed", w, FlowJournalWriter::failed).description("Transfers not written: write error").register(registry);
            Gauge.builder("oci.flow_journal.backlog", w, FlowJournalWriter::backlog).description("Transfers waiting to be written").register(registry);
        };
    }
}
