package com.storeops.alerts.events;

import com.storeops.alerts.model.AlertType;
import com.storeops.alerts.service.AlertService;
import com.storeops.shared.events.ActivityAssignedEvent;
import com.storeops.shared.events.ActivityStatusChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Subscribes to domain events on the event bus and turns them into alerts. */
@Component
public class AlertEventListener {

    private static final String BLOCKED = "BLOCKED";

    private final AlertService alertService;

    public AlertEventListener(AlertService alertService) {
        this.alertService = alertService;
    }

    @EventListener
    public void onActivityAssigned(ActivityAssignedEvent event) {
        alertService.raise(event.assigneeId(), AlertType.SHIFT_HANDOVER,
                "You have been assigned '" + event.title() + "' (" + event.priority() + ")", event.activityId());
    }

    @EventListener
    public void onActivityStatusChanged(ActivityStatusChangedEvent event) {
        if (BLOCKED.equals(event.newStatus())) {
            alertService.raise(event.ownerId(), AlertType.ESCALATION,
                    "Activity '" + event.title() + "' is BLOCKED", event.activityId());
        }
    }
}
