package io.openchain.app.labels;

import java.util.ArrayList;
import java.util.List;

public final class InMemoryLabelReportRepository implements LabelReportRepository {
    private final List<LabelReportStore.Report> reports = new ArrayList<>();

    @Override public synchronized void insert(LabelReportStore.Report r) { reports.addFirst(r); }
    @Override public synchronized List<LabelReportStore.Report> findAll() { return List.copyOf(reports); }
}
