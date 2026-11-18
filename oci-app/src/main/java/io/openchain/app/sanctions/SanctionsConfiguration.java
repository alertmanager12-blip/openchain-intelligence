package io.openchain.app.sanctions;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.openchain.app.config.OciProperties;
import io.openchain.app.labels.LabelCatalog;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Official sanctions lists (ADR-0027): the stored list applied at start, then fetched in the background when enabled. */
@Configuration
public class SanctionsConfiguration {
    private static final Logger log = LoggerFactory.getLogger(SanctionsConfiguration.class);

    @Bean
    SanctionsListService sanctionsListService(SanctionsListStore store, LabelCatalog labels, OciProperties props, Clock clock) {
        OciProperties.Sanctions s = props.getSanctions();
        return new SanctionsListService(store, labels, httpDownload(s.getOfacUrl()), clock, s.getMaxDrop(), s.getStaleAfter());
    }

    /** Streams the document (about 130 MB) straight into the parser: no temporary file on the read-only container. */
    static Supplier<InputStream> httpDownload(String url) {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build();
        return () -> {
            try {
                HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10))
                        .header("Accept", "application/xml").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                if (r.statusCode() != 200) {
                    r.body().close();
                    throw new IOException("HTTP " + r.statusCode());
                }
                return r.body();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UncheckedIOException(new IOException("interrupted"));
            }
        };
    }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService sanctionsTicker() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "oci-sanctions");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    ApplicationRunner startSanctionsLists(SanctionsListService service, OciProperties props, ScheduledExecutorService sanctionsTicker) {
        return args -> {
            if (props.getDemo().isEnabled()) return; // demo labels are fictional; no official list is mixed with them
            try {
                service.loadStored();
            } catch (RuntimeException e) {
                log.warn("sanctions list: stored publication not re-applied: {}", e.toString());
            }
            OciProperties.Sanctions s = props.getSanctions();
            if (!s.isOfacEnabled()) {
                log.warn("sanctions list: OFAC fetch is off (OCI_SANCTIONS_OFAC_ENABLED); screening uses {}",
                        service.status().state() == SanctionsListService.State.NONE ? "NO official list" : "the stored publication only");
                return;
            }
            long every = Math.max(Duration.ofMinutes(10).toMillis(), s.getRefreshInterval().toMillis());
            sanctionsTicker.scheduleWithFixedDelay(() -> {
                try {
                    service.refresh();
                } catch (RuntimeException e) {
                    log.warn("sanctions list refresh failed: {}", e.toString());
                }
            }, 0, every, TimeUnit.MILLISECONDS);
            log.info("sanctions list: OFAC SDN fetched now and every {}", Duration.ofMillis(every));
        };
    }

    @Bean
    MeterBinder sanctionsMetrics(SanctionsListService service, Clock clock) {
        return registry -> {
            Gauge.builder("oci.sanctions.addresses", service, x -> { var st = x.status(); return st.tron() + st.ethereum(); })
                    .description("Sanctioned addresses in force (OFAC SDN, indexed networks)").register(registry);
            Gauge.builder("oci.sanctions.unconfirmed_hours", service, x -> {
                var at = x.status().checkedAt();
                return at == null ? Double.NaN : Duration.between(at, clock.instant()).toMinutes() / 60.0;
            }).description("Hours since the list in force was last confirmed against the source").register(registry);
        };
    }
}
