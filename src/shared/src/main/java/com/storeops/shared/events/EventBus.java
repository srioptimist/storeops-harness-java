package com.storeops.shared.events;

/** In-process event bus used for all cross-module side effects. */
public interface EventBus {

    void emit(DomainEvent event);
}
