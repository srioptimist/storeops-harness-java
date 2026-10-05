package com.storeops.activities.routes;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeops.activities.model.ActivityAuditEntry;
import com.storeops.activities.model.TaskStatus;
import com.storeops.activities.service.ActivityService;
import com.storeops.shared.error.GlobalExceptionHandler;
import com.storeops.shared.error.NotFoundError;
import com.storeops.staff.api.AuthenticatedUser;
import com.storeops.staff.api.StaffDirectory;
import com.storeops.staff.model.StaffRole;
import com.storeops.staff.routes.StaffWebConfig;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ActivityController.class)
@Import({GlobalExceptionHandler.class, StaffWebConfig.class})
class ActivityControllerAuditTest {

    private static final String TOKEN = "Bearer t-lead";
    private static final AuthenticatedUser LEAD =
            new AuthenticatedUser("staff-lead-001", "store-001", "region-north", StaffRole.DEPARTMENT_LEAD);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ActivityService service;

    @MockitoBean
    private StaffDirectory staffDirectory;

    @BeforeEach
    void authenticate() {
        when(staffDirectory.authenticate("t-lead")).thenReturn(Optional.of(LEAD));
    }

    @Test
    @DisplayName("AC-2.1 audit trail returns 200 with all entry fields, oldest first")
    void auditTrailReturnsEntriesOldestFirst() throws Exception {
        when(service.auditTrail(LEAD, "a1")).thenReturn(List.of(
                new ActivityAuditEntry("au-1", "b-1", "a1", "store-001", "staff-lead-001", TaskStatus.TODO,
                        TaskStatus.BLOCKED, "late shift", Instant.parse("2026-09-30T12:00:00Z")),
                new ActivityAuditEntry("au-2", "b-2", "a1", "store-001", "staff-lead-001", TaskStatus.BLOCKED,
                        TaskStatus.DONE, "night handover", Instant.parse("2026-09-30T13:00:00Z"))));

        mockMvc.perform(get("/api/activities/a1/audit").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].newStatus").value("BLOCKED"))
                .andExpect(jsonPath("$[0].batchId").value("b-1"))
                .andExpect(jsonPath("$[1].newStatus").value("DONE"))
                .andExpect(jsonPath("$[1].batchId").value("b-2"))
                .andExpect(jsonPath("$[0].id").value("au-1"))
                .andExpect(jsonPath("$[0].activityId").value("a1"))
                .andExpect(jsonPath("$[0].storeId").value("store-001"))
                .andExpect(jsonPath("$[0].actorId").value("staff-lead-001"))
                .andExpect(jsonPath("$[0].previousStatus").value("TODO"))
                .andExpect(jsonPath("$[0].note").value("late shift"))
                .andExpect(jsonPath("$[0].occurredAt").exists())
                .andExpect(jsonPath("$[1].id").value("au-2"))
                .andExpect(jsonPath("$[1].activityId").value("a1"))
                .andExpect(jsonPath("$[1].storeId").value("store-001"))
                .andExpect(jsonPath("$[1].actorId").value("staff-lead-001"))
                .andExpect(jsonPath("$[1].previousStatus").value("BLOCKED"))
                .andExpect(jsonPath("$[1].note").value("night handover"))
                .andExpect(jsonPath("$[1].occurredAt").exists());
    }

    @Test
    @DisplayName("AC-2.1 audit trail without a bearer token returns 401 UNAUTHORIZED")
    void auditTrailRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/activities/a1/audit"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verify(service, never()).auditTrail(any(), any());
    }

    @Test
    @DisplayName("AC-2.2 audit trail of an activity outside the caller's store returns 404 NOT_FOUND")
    void auditTrailOfOtherStoreIsNotFound() throws Exception {
        when(service.auditTrail(LEAD, "b1")).thenThrow(new NotFoundError("Activity", "b1"));

        mockMvc.perform(get("/api/activities/b1/audit").header("Authorization", TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.statusCode").value(404));
    }
}
