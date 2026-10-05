package com.storeops.activities.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeops.activities.model.Task;
import com.storeops.activities.model.TaskCategory;
import com.storeops.activities.model.TaskPriority;
import com.storeops.activities.model.TaskStatus;
import com.storeops.activities.repository.InMemoryActivityAuditRepository;
import com.storeops.activities.repository.InMemoryTaskRepository;
import com.storeops.programmes.api.ProgrammeDirectory;
import com.storeops.shared.error.ForbiddenError;
import com.storeops.shared.error.NotFoundError;
import com.storeops.shared.error.ValidationError;
import com.storeops.shared.events.ActivityAssignedEvent;
import com.storeops.shared.events.ActivityStatusChangedEvent;
import com.storeops.shared.events.EventBus;
import com.storeops.staff.api.AuthenticatedUser;
import com.storeops.staff.api.StaffDirectory;
import com.storeops.staff.model.StaffRole;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ActivityServiceTest {

    private static final AuthenticatedUser LEAD =
            new AuthenticatedUser("lead", "store-001", "region-north", StaffRole.DEPARTMENT_LEAD);
    private static final AuthenticatedUser ASSOCIATE =
            new AuthenticatedUser("assoc", "store-001", "region-north", StaffRole.ASSOCIATE);
    private static final AuthenticatedUser MANAGER =
            new AuthenticatedUser("mgr", "store-001", "region-north", StaffRole.STORE_MANAGER);
    private static final AuthenticatedUser OTHER_STORE =
            new AuthenticatedUser("other", "store-002", "region-north", StaffRole.STORE_MANAGER);

    private StaffDirectory staffDirectory;
    private ProgrammeDirectory programmeDirectory;
    private EventBus eventBus;
    private ActivityService service;

    @BeforeEach
    void setUp() {
        staffDirectory = mock(StaffDirectory.class);
        programmeDirectory = mock(ProgrammeDirectory.class);
        eventBus = mock(EventBus.class);
        when(staffDirectory.exists("assoc")).thenReturn(true);
        when(programmeDirectory.exists("prog-1")).thenReturn(true);
        service = new ActivityService(new InMemoryTaskRepository(), new InMemoryActivityAuditRepository(),
                staffDirectory, programmeDirectory, eventBus);
    }

    @Test
    void createAppliesDefaultsAndOwnership() {
        Task task = service.create(LEAD, new NewTask("Restock aisle 4", null, null, null, null, null, null));

        assertThat(task.status()).isEqualTo(TaskStatus.TODO);
        assertThat(task.priority()).isEqualTo(TaskPriority.MEDIUM);
        assertThat(task.category()).isEqualTo(TaskCategory.GENERAL);
        assertThat(task.ownerId()).isEqualTo("lead");
        assertThat(task.storeId()).isEqualTo("store-001");
        verify(eventBus, never()).emit(any());
    }

    @Test
    void createWithAssigneeEmitsAssignedEvent() {
        Task task = service.create(LEAD, new NewTask("Planogram reset", "Bay 12", TaskPriority.HIGH,
                TaskCategory.PLANOGRAM, "prog-1", "assoc", LocalDate.of(2026, 10, 1)));

        ArgumentCaptor<ActivityAssignedEvent> event = ArgumentCaptor.forClass(ActivityAssignedEvent.class);
        verify(eventBus).emit(event.capture());
        assertThat(event.getValue().activityId()).isEqualTo(task.id());
        assertThat(event.getValue().assigneeId()).isEqualTo("assoc");
        assertThat(event.getValue().priority()).isEqualTo("HIGH");
    }

    @Test
    void createRejectsUnknownProgrammeAndAssignee() {
        assertThatThrownBy(() -> service.create(LEAD, new NewTask("t", null, null, null, "nope", null, null)))
                .isInstanceOf(NotFoundError.class);
        assertThatThrownBy(() -> service.create(LEAD, new NewTask("t", null, null, null, null, "ghost", null)))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void listFiltersByProgrammeAndStatus() {
        service.create(LEAD, new NewTask("a", null, null, null, "prog-1", null, null));
        Task b = service.create(LEAD, new NewTask("b", null, null, null, null, null, null));
        service.update(LEAD, b.id(), new TaskChanges(TaskStatus.DONE, null, null, null));

        assertThat(service.list("prog-1", null)).extracting(Task::title).containsExactly("a");
        assertThat(service.list(null, TaskStatus.DONE)).extracting(Task::title).containsExactly("b");
        assertThat(service.list(null, null)).hasSize(2);
        assertThat(service.findByStore("store-001")).hasSize(2);
    }

    @Test
    void getThrowsNotFoundForUnknownId() {
        assertThatThrownBy(() -> service.get("missing"))
                .isInstanceOf(NotFoundError.class)
                .hasFieldOrPropertyWithValue("code", "NOT_FOUND");
    }

    @Test
    void updateChangesFieldsAndEmitsStatusEvent() {
        Task task = service.create(LEAD, new NewTask("Audit", null, null, null, null, null, null));

        Task updated = service.update(LEAD, task.id(),
                new TaskChanges(TaskStatus.BLOCKED, TaskPriority.CRITICAL, TaskCategory.AUDIT, "assoc"));

        assertThat(updated.status()).isEqualTo(TaskStatus.BLOCKED);
        assertThat(updated.priority()).isEqualTo(TaskPriority.CRITICAL);
        assertThat(updated.category()).isEqualTo(TaskCategory.AUDIT);
        assertThat(updated.assigneeId()).isEqualTo("assoc");
        assertThat(service.get(task.id())).isEqualTo(updated);
        verify(eventBus).emit(any(ActivityStatusChangedEvent.class));
        verify(eventBus).emit(any(ActivityAssignedEvent.class));
    }

    @Test
    void updateKeepsUnspecifiedFieldsAndSkipsEventsWhenUnchanged() {
        Task task = service.create(LEAD, new NewTask("Audit", null, TaskPriority.LOW, null, null, null, null));

        Task updated = service.update(LEAD, task.id(), new TaskChanges(null, TaskPriority.HIGH, null, null));

        assertThat(updated.status()).isEqualTo(TaskStatus.TODO);
        assertThat(updated.assigneeId()).isNull();
        verify(eventBus, never()).emit(any());
    }

    @Test
    void updateRejectsEmptyChangesAndOtherStores() {
        Task task = service.create(LEAD, new NewTask("Audit", null, null, null, null, null, null));

        assertThatThrownBy(() -> service.update(LEAD, task.id(), new TaskChanges(null, null, null, null)))
                .isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> service.update(OTHER_STORE, task.id(),
                new TaskChanges(TaskStatus.DONE, null, null, null)))
                .isInstanceOf(NotFoundError.class);
    }

    @Test
    void deleteAllowedForOwnerAndStoreManagerOnly() {
        Task mine = service.create(LEAD, new NewTask("mine", null, null, null, null, null, null));
        Task another = service.create(LEAD, new NewTask("another", null, null, null, null, null, null));

        assertThatThrownBy(() -> service.delete(ASSOCIATE, mine.id())).isInstanceOf(ForbiddenError.class);

        service.delete(LEAD, mine.id());
        service.delete(MANAGER, another.id());

        assertThat(service.list(null, null)).isEmpty();
    }
}
