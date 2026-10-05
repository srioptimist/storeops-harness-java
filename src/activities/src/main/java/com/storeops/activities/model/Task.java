package com.storeops.activities.model;

import java.time.Instant;
import java.time.LocalDate;

/** An operational activity performed in a store. Immutable. */
public record Task(
        String id,
        String storeId,
        String programmeId,
        String title,
        String description,
        TaskStatus status,
        TaskPriority priority,
        TaskCategory category,
        String assigneeId,
        String ownerId,
        LocalDate dueDate,
        Instant createdAt,
        Instant updatedAt) {

    public Task withChanges(TaskStatus newStatus, TaskPriority newPriority, TaskCategory newCategory,
            String newAssigneeId, Instant now) {
        return new Task(id, storeId, programmeId, title, description, newStatus, newPriority, newCategory,
                newAssigneeId, ownerId, dueDate, createdAt, now);
    }
}
