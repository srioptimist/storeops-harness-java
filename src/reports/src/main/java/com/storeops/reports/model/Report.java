package com.storeops.reports.model;

import java.time.Instant;
import java.util.Map;

/** A generated performance report. Immutable. */
public record Report(
        String id,
        ReportType type,
        ReportStatus status,
        String scopeId,
        Map<String, Long> metrics,
        Instant generatedAt) {

    public Report {
        metrics = Map.copyOf(metrics);
    }
}
