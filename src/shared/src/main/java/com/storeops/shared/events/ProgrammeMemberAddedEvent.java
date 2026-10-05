package com.storeops.shared.events;

import java.time.Instant;

/** Raised by programmes when a staff member joins a programme. */
public record ProgrammeMemberAddedEvent(
        String programmeId,
        String programmeName,
        String staffId,
        String role,
        Instant occurredAt) implements DomainEvent {
}
