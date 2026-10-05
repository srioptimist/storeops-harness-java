package com.storeops.reports.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeops.activities.api.ActivityDirectory;
import com.storeops.activities.model.Task;
import com.storeops.activities.model.TaskCategory;
import com.storeops.activities.model.TaskPriority;
import com.storeops.activities.model.TaskStatus;
import com.storeops.reports.model.Report;
import com.storeops.reports.model.ReportStatus;
import com.storeops.reports.model.ReportType;
import com.storeops.reports.repository.InMemoryReportRepository;
import com.storeops.shared.error.NotFoundError;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

    @Test
    void storeSummaryAggregatesTaskMetrics() {
        ActivityDirectory activities = mock(ActivityDirectory.class);
        when(activities.findByStore("store-001")).thenReturn(List.of(
                task("1", TaskStatus.DONE, LocalDate.of(2026, 9, 1)),
                task("2", TaskStatus.BLOCKED, LocalDate.of(2026, 9, 1)),
                task("3", TaskStatus.TODO, LocalDate.of(2026, 12, 1)),
                task("4", TaskStatus.IN_PROGRESS, null)));
        ReportService service = new ReportService(activities, new InMemoryReportRepository(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        Report report = service.generateStoreSummary("store-001");

        assertThat(report.type()).isEqualTo(ReportType.STORE_SUMMARY);
        assertThat(report.status()).isEqualTo(ReportStatus.READY);
        assertThat(report.generatedAt()).isEqualTo(NOW);
        assertThat(report.metrics()).containsEntry("total", 4L).containsEntry("done", 1L)
                .containsEntry("blocked", 1L).containsEntry("overdue", 1L);
        assertThat(service.get(report.id())).isEqualTo(report);
    }

    @Test
    void getUnknownReportThrowsNotFound() {
        ReportService service = new ReportService(mock(ActivityDirectory.class), new InMemoryReportRepository());

        assertThatThrownBy(() -> service.get("missing")).isInstanceOf(NotFoundError.class);
    }

    private static Task task(String id, TaskStatus status, LocalDate due) {
        return new Task(id, "store-001", null, "t" + id, null, status, TaskPriority.MEDIUM, TaskCategory.GENERAL,
                null, "owner", due, NOW, NOW);
    }
}
