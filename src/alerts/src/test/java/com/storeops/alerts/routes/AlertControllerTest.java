package com.storeops.alerts.routes;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeops.alerts.model.AlertType;
import com.storeops.alerts.model.Notification;
import com.storeops.alerts.model.NotificationChannel;
import com.storeops.alerts.model.NotificationStatus;
import com.storeops.alerts.service.AlertService;
import com.storeops.shared.error.GlobalExceptionHandler;
import com.storeops.staff.api.AuthenticatedUser;
import com.storeops.staff.api.StaffDirectory;
import com.storeops.staff.model.StaffRole;
import com.storeops.staff.routes.StaffWebConfig;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AlertController.class)
@Import({GlobalExceptionHandler.class, StaffWebConfig.class})
class AlertControllerTest {

    private static final AuthenticatedUser ASSOCIATE =
            new AuthenticatedUser("assoc", "store-001", "region-north", StaffRole.ASSOCIATE);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AlertService service;

    @MockitoBean
    private StaffDirectory staffDirectory;

    @Test
    void returnsAlertsForAuthenticatedUser() throws Exception {
        when(staffDirectory.authenticate("t-assoc")).thenReturn(Optional.of(ASSOCIATE));
        when(service.listForUser(ASSOCIATE)).thenReturn(List.of(new Notification("n1", "assoc",
                AlertType.SLA_BREACH, NotificationChannel.IN_APP, NotificationStatus.UNREAD, "Overdue", "t1",
                Instant.parse("2026-09-30T08:00:00Z"))));

        mockMvc.perform(get("/api/alerts").header("Authorization", "Bearer t-assoc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("SLA_BREACH"))
                .andExpect(jsonPath("$[0].recipientId").value("assoc"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/alerts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
