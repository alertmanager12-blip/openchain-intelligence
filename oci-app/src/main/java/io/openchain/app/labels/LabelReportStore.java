package io.openchain.app.labels;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Community label reports. They enter moderation (PENDING) and are never published automatically.
 * TODO(persistence): moderation workflow, reporter identity, evidence URL checks.
 */
@Service
public class LabelReportStore {

    public record Report(String id, String network, String address, String category, String description,
                         List<String> evidenceUrls, String status, String reporter, Instant createdAt) {
        public Report { evidenceUrls = List.copyOf(evidenceUrls); }
    }

    private final Clock clock;
    private final List<Report> reports = new ArrayList<>();

    public LabelReportStore(Clock clock) { this.clock = clock; }

    public synchronized Report submit(String network, String address, String category, String description,
                                      List<String> evidenceUrls, String reporter) {
        Report r = new Report(UUID.randomUUID().toString(), network, address, category, description, evidenceUrls, "PENDING",
                reporter, clock.instant());
        reports.addFirst(r);
        return r;
    }

    public synchronized List<Report> list() { return List.copyOf(reports); }
}
