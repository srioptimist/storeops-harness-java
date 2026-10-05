package com.storeops.alerts.service;

import com.storeops.alerts.model.AlertType;
import com.storeops.alerts.model.Notification;
import com.storeops.alerts.model.NotificationChannel;
import com.storeops.alerts.model.NotificationStatus;
import com.storeops.alerts.repository.NotificationRepository;
import com.storeops.shared.error.ValidationError;
import com.storeops.staff.api.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Alert business logic. Alerts are only created in reaction to events on the event bus (see
 * {@code AlertEventListener}); no other module calls this service directly.
 */
@Service
public class AlertService {

    private final NotificationRepository repository;

    public AlertService(NotificationRepository repository) {
        this.repository = repository;
    }

    public List<Notification> listForUser(AuthenticatedUser caller) {
        return repository.findByRecipient(caller.userId());
    }

    public Notification raise(String recipientId, AlertType type, String message, String referenceId) {
        if (recipientId == null || recipientId.isBlank()) {
            throw new ValidationError("Alert recipient is required");
        }
        Notification notification = new Notification(UUID.randomUUID().toString(), recipientId, type,
                NotificationChannel.IN_APP, NotificationStatus.UNREAD, message, referenceId, Instant.now());
        return repository.save(notification);
    }
}
