package com.storeops.reports.repository;

import com.storeops.reports.model.Report;
import java.util.Optional;

/** Data access for generated reports. Internal to the reports module. */
public interface ReportRepository {

    Report save(Report report);

    Optional<Report> findById(String id);
}
