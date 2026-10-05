package com.storeops.alerts.repository;

import com.storeops.alerts.model.Notification;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/** Thread-safe in-memory notification store. */
@Repository
public class InMemoryNotificationRepository implements NotificationRepository {

    private final Map<String, Notification> notifications = new ConcurrentHashMap<>();

    @Override
    public Notification save(Notification notification) {
        notifications.put(notification.id(), notification);
        return notification;
    }

    @Override
    public List<Notification> findByRecipient(String recipientId) {
        return notifications.values().stream()
                .filter(n -> n.recipientId().equals(recipientId))
                .sorted(Comparator.comparing(Notification::createdAt).reversed())
                .toList();
    }
}
