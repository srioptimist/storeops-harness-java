package com.storeops.activities.routes;

import com.storeops.activities.model.TaskCategory;
import com.storeops.activities.model.TaskPriority;
import com.storeops.activities.model.TaskStatus;
import com.storeops.activities.service.TaskChanges;

/** Body of {@code PATCH /api/activities/{id}}; all fields optional. */
public record UpdateActivityRequest(
        TaskStatus status,
        TaskPriority priority,
        TaskCategory category,
        String assigneeId) {

    TaskChanges toCommand() {
        return new TaskChanges(status, priority, category, assigneeId);
    }
}
