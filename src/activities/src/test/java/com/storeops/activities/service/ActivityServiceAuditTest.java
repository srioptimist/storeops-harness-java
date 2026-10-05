package com.storeops.activities.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.storeops.activities.model.ActivityAuditEntry;
import com.storeops.activities.model.Task;
import com.storeops.activities.model.TaskCategory;
import com.storeops.activities.model.TaskPriority;
import com.storeops.activities.model.TaskStatus;
import com.storeops.activities.repository.InMemoryActivityAuditRepository;
import com.storeops.activities.repository.InMemoryTaskRepository;
import com.storeops.programmes.api.ProgrammeDirectory;
import com.storeops.shared.error.NotFoundError;
import com.storeops.shared.events.EventBus;
import com.storeops.staff.api.AuthenticatedUser;
import com.storeops.staff.api.StaffDirectory;
import com.storeops.staff.model.StaffRole;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ActivityServiceAuditTest {

    private static final Instant T0 = Instant.parse("2026-09-30T12:00:00Z");
    private static final AuthenticatedUser LEAD =
            new AuthenticatedUser("staff-lead-001", "store-001", "region-north", StaffRole.DEPARTMENT_LEAD);
    private static final AuthenticatedUser OTHER_STORE_MANAGER =
            new AuthenticatedUser("staff-manager-002", "store-002", "region-north", StaffRole.STORE_MANAGER);

    private InMemoryTaskRepository tasks;
    private InMemoryActivityAuditRepository audits;
    private ActivityService service;

    @BeforeEach
    void setUp() {
        tasks = new InMemoryTaskRepository();
        audits = new InMemoryActivityAuditRepository();
        service = new ActivityService(tasks, audits, mock(StaffDirectory.class), mock(ProgrammeDirectory.class),
                mock(EventBus.class));
    }

    @Test
    @DisplayName("AC-2.2 audit trail of another store's activity throws NOT_FOUND (404)")
    void auditTrailOfOtherStoreActivityIsNotFound() {
        seed("b1", "store-002");
        audits.save(entry("au-1", "b1", "store-002", TaskStatus.DONE, T0));

        assertThatThrownBy(() -> service.auditTrail(LEAD, "b1"))
                .isInstanceOf(NotFoundError.class)
                .hasFieldOrPropertyWithValue("code", "NOT_FOUND")
                .hasFieldOrPropertyWithValue("statusCode", 404);

        assertThat(service.auditTrail(OTHER_STORE_MANAGER, "b1")).extracting(ActivityAuditEntry::id)
                .containsExactly("au-1");
    }

    @Test
    @DisplayName("AC-2.2 audit trail of an unknown activity throws NOT_FOUND")
    void auditTrailOfUnknownActivityIsNotFound() {
        assertThatThrownBy(() -> service.auditTrail(LEAD, "missing"))
                .isInstanceOf(NotFoundError.class)
                .hasFieldOrPropertyWithValue("code", "NOT_FOUND");
    }

    @Test
    @DisplayName("AC-2.1 audit trail of an own-store activity lists only its entries, oldest first")
    void auditTrailListsOwnEntriesOldestFirst() {
        seed("a1", "store-001");
        seed("a2", "store-001");
        audits.save(entry("au-late", "a1", "store-001", TaskStatus.DONE, T0.plusSeconds(60)));
        audits.save(entry("au-early", "a1", "store-001", TaskStatus.BLOCKED, T0));
        audits.save(entry("au-other", "a2", "store-001", TaskStatus.DONE, T0));

        List<ActivityAuditEntry> trail = service.auditTrail(LEAD, "a1");

        assertThat(trail).extracting(ActivityAuditEntry::id).containsExactly("au-early", "au-late");
        assertThat(trail).extracting(ActivityAuditEntry::newStatus)
                .containsExactly(TaskStatus.BLOCKED, TaskStatus.DONE);
    }

    private void seed(String id, String storeId) {
        tasks.save(new Task(id, storeId, null, "Task " + id, null, TaskStatus.TODO, TaskPriority.MEDIUM,
                TaskCategory.GENERAL, null, "staff-manager-001", null, T0, T0));
    }

    private static ActivityAuditEntry entry(String id, String activityId, String storeId, TaskStatus newStatus,
            Instant at) {
        return new ActivityAuditEntry(id, "batch-" + id, activityId, storeId, "staff-lead-001", TaskStatus.TODO,
                newStatus, null, at);
    }
}
