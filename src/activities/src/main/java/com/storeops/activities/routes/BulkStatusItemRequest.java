package com.storeops.activities.routes;

import com.storeops.activities.model.TaskStatus;
import com.storeops.activities.service.BulkStatusItem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** One item of the {@code PATCH /api/activities/bulk-status} body. */
public record BulkStatusItemRequest(@NotBlank String activityId, @NotNull TaskStatus status) {

    BulkStatusItem toCommand() {
        return new BulkStatusItem(activityId, status);
    }
}
