package com.storeops.activities.service;

import com.storeops.activities.model.TaskCategory;
import com.storeops.activities.model.TaskPriority;
import com.storeops.activities.model.TaskStatus;

/** Partial update of an activity; {@code null} fields are left unchanged. */
public record TaskChanges(TaskStatus status, TaskPriority priority, TaskCategory category, String assigneeId) {

    public boolean isEmpty() {
        return status == null && priority == null && category == null && assigneeId == null;
    }
}
