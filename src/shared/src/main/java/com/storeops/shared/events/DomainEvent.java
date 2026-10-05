package com.storeops.shared.events;

import java.time.Instant;

/**
 * Marker for events published on the StoreOps {@link EventBus}.
 *
 * <p>Events are the only permitted mechanism for cross-module side effects. They live in the
 * shared kernel and use primitive/String payloads so publishers and listeners never import each
 * other's modules.
 */
public interface DomainEvent {

    Instant occurredAt();
}
