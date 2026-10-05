package com.storeops.activities.model;

import java.time.Instant;

/** Immutable audit record of one activity status change made by a bulk status update. */
public record ActivityAuditEntry(
        String id,
        String batchId,
        String activityId,
        String storeId,
        String actorId,
        TaskStatus previousStatus,
        TaskStatus newStatus,
        String note,
        Instant occurredAt) {
}
