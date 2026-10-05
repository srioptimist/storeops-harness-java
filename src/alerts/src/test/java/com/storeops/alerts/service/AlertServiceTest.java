package com.storeops.alerts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeops.alerts.events.AlertEventListener;
import com.storeops.alerts.model.AlertType;
import com.storeops.alerts.model.Notification;
import com.storeops.alerts.model.NotificationChannel;
import com.storeops.alerts.model.NotificationStatus;
import com.storeops.alerts.repository.InMemoryNotificationRepository;
import com.storeops.shared.error.ValidationError;
import com.storeops.shared.events.ActivityAssignedEvent;
import com.storeops.shared.events.ActivityStatusChangedEvent;
import com.storeops.staff.api.AuthenticatedUser;
import com.storeops.staff.model.StaffRole;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AlertServiceTest {

    private static final AuthenticatedUser ASSOCIATE =
            new AuthenticatedUser("assoc", "store-001", "region-north", StaffRole.ASSOCIATE);
    private static final AuthenticatedUser OWNER =
            new AuthenticatedUser("owner", "store-001", "region-north", StaffRole.DEPARTMENT_LEAD);

    private AlertService service;
    private AlertEventListener listener;

    @BeforeEach
    void setUp() {
        service = new AlertService(new InMemoryNotificationRepository());
        listener = new AlertEventListener(service);
    }

    @Test
    void raiseStoresUnreadInAppAlertForRecipient() {
        Notification alert = service.raise("assoc", AlertType.INVENTORY, "Low stock: milk", "sku-1");

        assertThat(alert.channel()).isEqualTo(NotificationChannel.IN_APP);
        assertThat(alert.status()).isEqualTo(NotificationStatus.UNREAD);
        assertThat(service.listForUser(ASSOCIATE)).containsExactly(alert);
        assertThat(service.listForUser(OWNER)).isEmpty();
    }

    @Test
    void raiseRequiresRecipient() {
        assertThatThrownBy(() -> service.raise(" ", AlertType.INVENTORY, "x", null))
                .isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> service.raise(null, AlertType.INVENTORY, "x", null))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void assignmentEventAlertsAssignee() {
        listener.onActivityAssigned(new ActivityAssignedEvent("t1", "Restock", "HIGH", "assoc", "store-001",
                Instant.now()));

        assertThat(service.listForUser(ASSOCIATE)).singleElement()
                .satisfies(n -> {
                    assertThat(n.type()).isEqualTo(AlertType.SHIFT_HANDOVER);
                    assertThat(n.referenceId()).isEqualTo("t1");
                });
    }

    @Test
    void blockedStatusEscalatesToOwnerOtherStatusesDoNot() {
        listener.onActivityStatusChanged(new ActivityStatusChangedEvent("t1", "Audit", "TODO", "IN_PROGRESS",
                "owner", "store-001", Instant.now()));
        assertThat(service.listForUser(OWNER)).isEmpty();

        listener.onActivityStatusChanged(new ActivityStatusChangedEvent("t1", "Audit", "IN_PROGRESS", "BLOCKED",
                "owner", "store-001", Instant.now()));
        assertThat(service.listForUser(OWNER)).singleElement()
                .extracting(Notification::type).isEqualTo(AlertType.ESCALATION);
    }
}
