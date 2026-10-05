package com.storeops.shared.events;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class SpringEventBusTest {

    @Test
    void emitDelegatesToSpringPublisher() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        EventBus bus = new SpringEventBus(publisher);
        DomainEvent event = new ProgrammeMemberAddedEvent("p1", "Refit", "u1", "ASSOCIATE", Instant.now());

        bus.emit(event);

        verify(publisher).publishEvent(event);
    }
}
