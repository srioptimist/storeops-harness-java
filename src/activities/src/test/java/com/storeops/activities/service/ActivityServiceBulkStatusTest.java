package com.storeops.activities.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.storeops.activities.model.ActivityAuditEntry;
import com.storeops.activities.model.BulkItemOutcome;
import com.storeops.activities.model.Task;
import com.storeops.activities.model.TaskCategory;
import com.storeops.activities.model.TaskPriority;
import com.storeops.activities.model.TaskStatus;
import com.storeops.activities.repository.InMemoryActivityAuditRepository;
import com.storeops.activities.repository.InMemoryTaskRepository;
import com.storeops.programmes.api.ProgrammeDirectory;
import com.storeops.shared.error.ValidationError;
import com.storeops.shared.events.ActivityStatusChangedEvent;
import com.storeops.shared.events.EventBus;
import com.storeops.staff.api.AuthenticatedUser;
import com.storeops.staff.api.StaffDirectory;
import com.storeops.staff.model.StaffRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ActivityServiceBulkStatusTest {

    private static final Instant T0 = Instant.parse("2026-09-30T12:00:00Z");
    private static final AuthenticatedUser LEAD =
            new AuthenticatedUser("staff-lead-001", "store-001", "region-north", StaffRole.DEPARTMENT_LEAD);

    private InMemoryTaskRepository tasks;
    private InMemoryActivityAuditRepository audits;
    private EventBus eventBus;
    private ActivityService service;

    @BeforeEach
    void setUp() {
        tasks = new InMemoryTaskRepository();
        audits = new InMemoryActivityAuditRepository();
        eventBus = mock(EventBus.class);
        service = new ActivityService(tasks, audits, mock(StaffDirectory.class), mock(ProgrammeDirectory.class),
                eventBus);
    }

    @Test
    @DisplayName("AC-1.3 bulk update to a status other than DONE or BLOCKED throws VALIDATION_ERROR, changes nothing")
    void rejectsDisallowedTargetStatus() {
        seed("a1", "store-001", TaskStatus.TODO);

        assertThatThrownBy(() -> service.bulkUpdateStatus(LEAD,
                command(new BulkStatusItem("a1", TaskStatus.IN_PROGRESS))))
                .isInstanceOf(ValidationError.class)
                .hasFieldOrPropertyWithValue("code", "VALIDATION_ERROR");

        assertThat(service.get("a1").status()).isEqualTo(TaskStatus.TODO);
        assertThat(audits.findByActivityId("a1")).isEmpty();
        verify(eventBus, never()).emit(any());
    }

    @Test
    @DisplayName("AC-1.4 bulk update with a duplicate activity id throws VALIDATION_ERROR and changes nothing")
    void rejectsDuplicateActivityIds() {
        seed("a1", "store-001", TaskStatus.TODO);

        assertThatThrownBy(() -> service.bulkUpdateStatus(LEAD, command(
                new BulkStatusItem("a1", TaskStatus.DONE), new BulkStatusItem("a1", TaskStatus.BLOCKED))))
                .isInstanceOf(ValidationError.class)
                .hasFieldOrPropertyWithValue("code", "VALIDATION_ERROR");

        assertThat(service.get("a1").status()).isEqualTo(TaskStatus.TODO);
        assertThat(audits.findByActivityId("a1")).isEmpty();
        verify(eventBus, never()).emit(any());
    }

    @Test
    @DisplayName("AC-1.4 bulk update with no items, over 50 items, a blank id or no status throws VALIDATION_ERROR")
    void rejectsMalformedBatches() {
        seed("a1", "store-001", TaskStatus.TODO);
        List<BulkStatusItem> tooMany = new ArrayList<>();
        IntStream.rangeClosed(1, 51).forEach(n -> tooMany.add(new BulkStatusItem("a" + n, TaskStatus.DONE)));

        assertThatThrownBy(() -> service.bulkUpdateStatus(LEAD, new BulkStatusCommand(List.of(), null)))
                .isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> service.bulkUpdateStatus(LEAD, new BulkStatusCommand(null, null)))
                .isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> service.bulkUpdateStatus(LEAD, new BulkStatusCommand(tooMany, null)))
                .isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> service.bulkUpdateStatus(LEAD, command(new BulkStatusItem(" ", TaskStatus.DONE))))
                .isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> service.bulkUpdateStatus(LEAD, command(new BulkStatusItem(null, TaskStatus.DONE))))
                .isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> service.bulkUpdateStatus(LEAD, command(new BulkStatusItem("a1", null))))
                .isInstanceOf(ValidationError.class);

        assertThat(service.get("a1").status()).isEqualTo(TaskStatus.TODO);
        verify(eventBus, never()).emit(any());
    }

    @Test
    @DisplayName("AC-1.5 unknown and other-store items fail with NOT_FOUND while the own-store item is updated")
    void partialFailureUpdatesOnlyOwnStoreItems() {
        seed("a1", "store-001", TaskStatus.TODO);
        seed("b1", "store-002", TaskStatus.TODO);

        BulkStatusResult result = service.bulkUpdateStatus(LEAD, command(new BulkStatusItem("a1", TaskStatus.DONE),
                new BulkStatusItem("missing", TaskStatus.DONE), new BulkStatusItem("b1", TaskStatus.BLOCKED)));

        assertThat(result.requested()).isEqualTo(3);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.unchanged()).isZero();
        assertThat(result.failed()).isEqualTo(2);
        assertThat(result.results()).extracting(BulkItemResult::activityId).containsExactly("a1", "missing", "b1");
        assertThat(result.results()).extracting(BulkItemResult::outcome)
                .containsExactly(BulkItemOutcome.UPDATED, BulkItemOutcome.FAILED, BulkItemOutcome.FAILED);
        assertThat(result.results().get(1).reason()).isEqualTo("NOT_FOUND");
        assertThat(result.results().get(2).reason()).isEqualTo("NOT_FOUND");
        assertThat(service.get("a1").status()).isEqualTo(TaskStatus.DONE);
        assertThat(service.get("b1").status()).isEqualTo(TaskStatus.TODO);
        assertThat(audits.findByActivityId("b1")).isEmpty();
    }

    @Test
    @DisplayName("AC-1.6 an item already in the target status is UNCHANGED with no audit entry and no save")
    void itemAlreadyInTargetStatusIsUnchanged() {
        seed("a2", "store-001", TaskStatus.BLOCKED);

        BulkStatusResult result = service.bulkUpdateStatus(LEAD,
                command(new BulkStatusItem("a2", TaskStatus.BLOCKED)));

        assertThat(result.updated()).isZero();
        assertThat(result.unchanged()).isEqualTo(1);
        assertThat(result.results().get(0).outcome()).isEqualTo(BulkItemOutcome.UNCHANGED);
        assertThat(result.results().get(0).auditId()).isNull();
        assertThat(service.get("a2").updatedAt()).isEqualTo(T0);
        assertThat(audits.findByActivityId("a2")).isEmpty();
        verify(eventBus, never()).emit(any());
    }

    @Test
    @DisplayName("AC-1.7 one audit entry per updated task, sharing the batch id and linked from the results")
    void storesOneAuditEntryPerUpdatedTask() {
        seed("a1", "store-001", TaskStatus.TODO);
        seed("a3", "store-001", TaskStatus.IN_PROGRESS);

        BulkStatusResult result = service.bulkUpdateStatus(LEAD, new BulkStatusCommand(List.of(
                new BulkStatusItem("a1", TaskStatus.DONE), new BulkStatusItem("a3", TaskStatus.BLOCKED)), "handover"));

        List<ActivityAuditEntry> a1Entries = audits.findByActivityId("a1");
        List<ActivityAuditEntry> a3Entries = audits.findByActivityId("a3");
        assertThat(a1Entries).hasSize(1);
        assertThat(a3Entries).hasSize(1);
        ActivityAuditEntry a1 = a1Entries.get(0);
        ActivityAuditEntry a3 = a3Entries.get(0);
        assertThat(a1.previousStatus()).isEqualTo(TaskStatus.TODO);
        assertThat(a1.newStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(a1.actorId()).isEqualTo("staff-lead-001");
        assertThat(a1.storeId()).isEqualTo("store-001");
        assertThat(a1.note()).isEqualTo("handover");
        assertThat(a3.previousStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(a3.newStatus()).isEqualTo(TaskStatus.BLOCKED);
        assertThat(a1.batchId()).isEqualTo(result.batchId());
        assertThat(a3.batchId()).isEqualTo(result.batchId());
        assertThat(result.results().get(0).auditId()).isEqualTo(a1.id());
        assertThat(result.results().get(1).auditId()).isEqualTo(a3.id());
        assertThat(result.results().get(0).previousStatus()).isEqualTo(TaskStatus.TODO);
        assertThat(result.results().get(0).status()).isEqualTo(TaskStatus.DONE);
    }

    @Test
    @DisplayName("AC-1.8 exactly one ActivityStatusChangedEvent per updated task, emitted after both saves")
    void emitsOneEventPerUpdatedTaskAfterSaving() {
        seed("a1", "store-001", TaskStatus.TODO);
        seed("a2", "store-001", TaskStatus.BLOCKED);
        List<TaskStatus> statusAtEmit = new ArrayList<>();
        List<Integer> auditCountAtEmit = new ArrayList<>();
        doAnswer(invocation -> {
            statusAtEmit.add(tasks.findById("a1").orElseThrow().status());
            auditCountAtEmit.add(audits.findByActivityId("a1").size());
            return null;
        }).when(eventBus).emit(any());

        service.bulkUpdateStatus(LEAD, command(new BulkStatusItem("a1", TaskStatus.BLOCKED),
                new BulkStatusItem("a2", TaskStatus.BLOCKED), new BulkStatusItem("missing", TaskStatus.DONE)));

        ArgumentCaptor<ActivityStatusChangedEvent> event = ArgumentCaptor.forClass(ActivityStatusChangedEvent.class);
        verify(eventBus, times(1)).emit(event.capture());
        ActivityStatusChangedEvent emitted = event.getValue();
        assertThat(emitted.activityId()).isEqualTo("a1");
        assertThat(emitted.previousStatus()).isEqualTo("TODO");
        assertThat(emitted.newStatus()).isEqualTo("BLOCKED");
        assertThat(emitted.ownerId()).isEqualTo("staff-manager-001");
        assertThat(emitted.storeId()).isEqualTo("store-001");
        assertThat(emitted.occurredAt()).isNotNull();
        assertThat(statusAtEmit).containsExactly(TaskStatus.BLOCKED);
        assertThat(auditCountAtEmit).containsExactly(1);
    }

    private void seed(String id, String storeId, TaskStatus status) {
        tasks.save(new Task(id, storeId, null, "Task " + id, null, status, TaskPriority.MEDIUM,
                TaskCategory.GENERAL, null, "staff-manager-001", null, T0, T0));
    }

    private static BulkStatusCommand command(BulkStatusItem... items) {
        return new BulkStatusCommand(List.of(items), null);
    }
}
