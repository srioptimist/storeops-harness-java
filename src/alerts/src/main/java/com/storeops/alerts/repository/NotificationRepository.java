package com.storeops.alerts.repository;

import com.storeops.alerts.model.Notification;
import java.util.List;

/** Data access for notifications. Internal to the alerts module. */
public interface NotificationRepository {

    Notification save(Notification notification);

    /** Returns the recipient's notifications, newest first. */
    List<Notification> findByRecipient(String recipientId);
}
