package io.openchain.app.labels;

import java.util.List;

/** Label report storage ({@link InMemoryLabelReportRepository}, {@link JdbcLabelReportRepository}). */
public interface LabelReportRepository {
    void insert(LabelReportStore.Report report);
    /** Newest first. */
    List<LabelReportStore.Report> findAll();
}
