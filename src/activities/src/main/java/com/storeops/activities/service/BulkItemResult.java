package com.storeops.activities.service;

import com.storeops.activities.model.BulkItemOutcome;
import com.storeops.activities.model.TaskStatus;

/** Per-item result of a bulk status update; {@code reason} is set only for FAILED items. */
public record BulkItemResult(
        String activityId,
        BulkItemOutcome outcome,
        TaskStatus previousStatus,
        TaskStatus status,
        String auditId,
        String reason) {

    static BulkItemResult failed(String activityId, String reason) {
        return new BulkItemResult(activityId, BulkItemOutcome.FAILED, null, null, null, reason);
    }
}
