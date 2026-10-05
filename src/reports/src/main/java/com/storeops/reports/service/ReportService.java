package com.storeops.reports.service;

import com.storeops.activities.api.ActivityDirectory;
import com.storeops.activities.model.Task;
import com.storeops.activities.model.TaskStatus;
import com.storeops.reports.model.Report;
import com.storeops.reports.model.ReportStatus;
import com.storeops.reports.model.ReportType;
import com.storeops.reports.repository.ReportRepository;
import com.storeops.shared.error.NotFoundError;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Report generation. Read-only with respect to other modules: it only reads through their
 * {@code api} interfaces and writes exclusively to its own repository. Stub implementation.
 */
@Service
public class ReportService {

    private final ActivityDirectory activityDirectory;
    private final ReportRepository repository;
    private final Clock clock;

    @Autowired
    public ReportService(ActivityDirectory activityDirectory, ReportRepository repository) {
        this(activityDirectory, repository, Clock.systemUTC());
    }

    ReportService(ActivityDirectory activityDirectory, ReportRepository repository, Clock clock) {
        this.activityDirectory = activityDirectory;
        this.repository = repository;
        this.clock = clock;
    }

    public Report generateStoreSummary(String storeId) {
        List<Task> tasks = activityDirectory.findByStore(storeId);
        LocalDate today = LocalDate.now(clock);
        long done = tasks.stream().filter(t -> t.status() == TaskStatus.DONE).count();
        long blocked = tasks.stream().filter(t -> t.status() == TaskStatus.BLOCKED).count();
        long overdue = tasks.stream()
                .filter(t -> t.status() != TaskStatus.DONE && t.dueDate() != null && t.dueDate().isBefore(today))
                .count();
        Map<String, Long> metrics = Map.of(
                "total", (long) tasks.size(),
                "done", done,
                "blocked", blocked,
                "overdue", overdue);
        Report report = new Report(UUID.randomUUID().toString(), ReportType.STORE_SUMMARY, ReportStatus.READY,
                storeId, metrics, clock.instant());
        return repository.save(report);
    }

    public Report get(String id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundError("Report", id));
    }
}
