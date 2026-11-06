package io.openchain.app.labels;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Community label reports ({@link LabelReportRepository}: in memory or the {@code label_reports} table). They enter
 * moderation (PENDING) and are never published automatically.
 * TODO(persistence): moderation workflow, reporter identity, evidence URL checks.
 */
@Service
public class LabelReportStore {

    public record Report(String id, String network, String address, String category, String description,
                         List<String> evidenceUrls, String status, String reporter, Instant createdAt) {
        public Report { evidenceUrls = List.copyOf(evidenceUrls); }
    }

    private final Clock clock;
    private final LabelReportRepository repository;

    public LabelReportStore(Clock clock, LabelReportRepository repository) {
        this.clock = clock;
        this.repository = repository;
    }

    public Report submit(String network, String address, String category, String description,
                         List<String> evidenceUrls, String reporter) {
        Report r = new Report(UUID.randomUUID().toString(), network, address, category, description, evidenceUrls, "PENDING",
                reporter, clock.instant());
        repository.insert(r);
        return r;
    }

    /** Newest first. */
    public List<Report> list() { return repository.findAll(); }
}
