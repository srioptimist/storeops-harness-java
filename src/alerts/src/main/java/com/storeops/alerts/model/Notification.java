package com.storeops.alerts.model;

import java.time.Instant;

/** An alert delivered to a staff member. Immutable. */
public record Notification(
        String id,
        String recipientId,
        AlertType type,
        NotificationChannel channel,
        NotificationStatus status,
        String message,
        String referenceId,
        Instant createdAt) {
}
