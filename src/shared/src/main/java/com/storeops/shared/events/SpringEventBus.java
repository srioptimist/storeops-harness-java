package com.storeops.shared.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * {@link EventBus} backed by Spring's {@link ApplicationEventPublisher}. Listeners subscribe with
 * {@code @EventListener} on the concrete event type. Delivery is synchronous.
 */
@Component
public class SpringEventBus implements EventBus {

    private static final Logger LOG = LoggerFactory.getLogger(SpringEventBus.class);

    private final ApplicationEventPublisher publisher;

    public SpringEventBus(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void emit(DomainEvent event) {
        LOG.debug("Emitting {}", event);
        publisher.publishEvent(event);
    }
}
