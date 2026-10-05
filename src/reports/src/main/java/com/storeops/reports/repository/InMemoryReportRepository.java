package com.storeops.reports.repository;

import com.storeops.reports.model.Report;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/** Thread-safe in-memory report store. */
@Repository
public class InMemoryReportRepository implements ReportRepository {

    private final Map<String, Report> reports = new ConcurrentHashMap<>();

    @Override
    public Report save(Report report) {
        reports.put(report.id(), report);
        return report;
    }

    @Override
    public Optional<Report> findById(String id) {
        return Optional.ofNullable(reports.get(id));
    }
}
