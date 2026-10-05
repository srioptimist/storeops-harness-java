package com.storeops.shared.events;

import java.time.Instant;

/** Raised by activities when a task's status changes. */
public record ActivityStatusChangedEvent(
        String activityId,
        String title,
        String previousStatus,
        String newStatus,
        String ownerId,
        String storeId,
        Instant occurredAt) implements DomainEvent {
}
