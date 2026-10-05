package com.storeops.activities.service;

import com.storeops.activities.model.TaskCategory;
import com.storeops.activities.model.TaskPriority;
import java.time.LocalDate;

/** Input for creating an activity. {@code null} priority/category fall back to defaults. */
public record NewTask(
        String title,
        String description,
        TaskPriority priority,
        TaskCategory category,
        String programmeId,
        String assigneeId,
        LocalDate dueDate) {
}
