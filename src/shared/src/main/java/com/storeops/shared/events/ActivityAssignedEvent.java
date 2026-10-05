package com.storeops.shared.events;

import java.time.Instant;

/** Raised by activities when a task is assigned (on create or reassignment). */
public record ActivityAssignedEvent(
        String activityId,
        String title,
        String priority,
        String assigneeId,
        String storeId,
        Instant occurredAt) implements DomainEvent {
}
