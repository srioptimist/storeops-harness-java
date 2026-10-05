package com.storeops.activities.routes;

import com.storeops.activities.model.TaskCategory;
import com.storeops.activities.model.TaskPriority;
import com.storeops.activities.service.NewTask;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Body of {@code POST /api/activities}. */
public record CreateActivityRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 2000) String description,
        TaskPriority priority,
        TaskCategory category,
        String programmeId,
        String assigneeId,
        LocalDate dueDate) {

    NewTask toCommand() {
        return new NewTask(title, description, priority, category, programmeId, assigneeId, dueDate);
    }
}
