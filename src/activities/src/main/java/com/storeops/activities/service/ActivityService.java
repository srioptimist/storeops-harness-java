package com.storeops.activities.service;

import com.storeops.activities.api.ActivityDirectory;
import com.storeops.activities.model.Task;
import com.storeops.activities.model.TaskCategory;
import com.storeops.activities.model.TaskPriority;
import com.storeops.activities.model.TaskStatus;
import com.storeops.activities.repository.TaskRepository;
import com.storeops.programmes.api.ProgrammeDirectory;
import com.storeops.shared.error.ForbiddenError;
import com.storeops.shared.error.NotFoundError;
import com.storeops.shared.error.ValidationError;
import com.storeops.shared.events.ActivityAssignedEvent;
import com.storeops.shared.events.ActivityStatusChangedEvent;
import com.storeops.shared.events.EventBus;
import com.storeops.staff.api.AuthenticatedUser;
import com.storeops.staff.api.StaffDirectory;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Activity business logic. Stub implementation: CRUD plus the minimal rules from the spec.
 * Cross-module side effects (alerts) are raised via the {@link EventBus} only.
 */
@Service
public class ActivityService implements ActivityDirectory {

    private static final String RESOURCE = "Activity";

    private final TaskRepository repository;
    private final StaffDirectory staffDirectory;
    private final ProgrammeDirectory programmeDirectory;
    private final EventBus eventBus;

    public ActivityService(TaskRepository repository, StaffDirectory staffDirectory,
            ProgrammeDirectory programmeDirectory, EventBus eventBus) {
        this.repository = repository;
        this.staffDirectory = staffDirectory;
        this.programmeDirectory = programmeDirectory;
        this.eventBus = eventBus;
    }

    public List<Task> list(String programmeId, TaskStatus status) {
        return repository.findAll(programmeId, status);
    }

    public Task get(String id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundError(RESOURCE, id));
    }

    public Task create(AuthenticatedUser caller, NewTask input) {
        if (input.programmeId() != null && !programmeDirectory.exists(input.programmeId())) {
            throw new NotFoundError("Programme", input.programmeId());
        }
        requireStaff(input.assigneeId());
        Instant now = Instant.now();
        Task task = new Task(UUID.randomUUID().toString(), caller.storeId(), input.programmeId(),
                input.title(), input.description(), TaskStatus.TODO,
                Objects.requireNonNullElse(input.priority(), TaskPriority.MEDIUM),
                Objects.requireNonNullElse(input.category(), TaskCategory.GENERAL),
                input.assigneeId(), caller.userId(), input.dueDate(), now, now);
        Task saved = repository.save(task);
        if (saved.assigneeId() != null) {
            emitAssigned(saved, now);
        }
        return saved;
    }

    public Task update(AuthenticatedUser caller, String id, TaskChanges changes) {
        if (changes.isEmpty()) {
            throw new ValidationError("At least one of status, priority, category, assigneeId is required");
        }
        Task current = findInCallerStore(caller, id);
        requireStaff(changes.assigneeId());
        Instant now = Instant.now();
        Task updated = repository.save(current.withChanges(
                Objects.requireNonNullElse(changes.status(), current.status()),
                Objects.requireNonNullElse(changes.priority(), current.priority()),
                Objects.requireNonNullElse(changes.category(), current.category()),
                changes.assigneeId() != null ? changes.assigneeId() : current.assigneeId(),
                now));
        if (updated.status() != current.status()) {
            eventBus.emit(new ActivityStatusChangedEvent(updated.id(), updated.title(), current.status().name(),
                    updated.status().name(), updated.ownerId(), updated.storeId(), now));
        }
        if (changes.assigneeId() != null && !changes.assigneeId().equals(current.assigneeId())) {
            emitAssigned(updated, now);
        }
        return updated;
    }

    public void delete(AuthenticatedUser caller, String id) {
        Task task = findInCallerStore(caller, id);
        if (!task.ownerId().equals(caller.userId()) && !caller.isStoreManager()) {
            throw new ForbiddenError("Only the activity owner or a store manager may delete an activity");
        }
        repository.deleteById(id);
    }

    @Override
    public List<Task> findByStore(String storeId) {
        return repository.findByStore(storeId);
    }

    private Task findInCallerStore(AuthenticatedUser caller, String id) {
        return repository.findById(id)
                .filter(t -> t.storeId().equals(caller.storeId()))
                .orElseThrow(() -> new NotFoundError(RESOURCE, id));
    }

    private void requireStaff(String staffId) {
        if (staffId != null && !staffDirectory.exists(staffId)) {
            throw new ValidationError("Assignee '" + staffId + "' is not a known staff member");
        }
    }

    private void emitAssigned(Task task, Instant now) {
        eventBus.emit(new ActivityAssignedEvent(task.id(), task.title(), task.priority().name(),
                task.assigneeId(), task.storeId(), now));
    }
}
