package com.storeops.activities.routes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeops.activities.model.BulkItemOutcome;
import com.storeops.activities.model.TaskStatus;
import com.storeops.activities.service.ActivityService;
import com.storeops.activities.service.BulkItemResult;
import com.storeops.activities.service.BulkStatusCommand;
import com.storeops.activities.service.BulkStatusItem;
import com.storeops.activities.service.BulkStatusResult;
import com.storeops.shared.error.GlobalExceptionHandler;
import com.storeops.staff.api.AuthenticatedUser;
import com.storeops.staff.api.StaffDirectory;
import com.storeops.staff.model.StaffRole;
import com.storeops.staff.routes.StaffWebConfig;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ActivityController.class)
@Import({GlobalExceptionHandler.class, StaffWebConfig.class})
class ActivityControllerBulkStatusTest {

    private static final String URL = "/api/activities/bulk-status";
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
    @DisplayName("AC-1.1 bulk update returns 200 with per-item results and passes items in request order")
    void bulkUpdateReturnsPerItemResults() throws Exception {
        BulkStatusResult result = new BulkStatusResult("b-1", 2, 1, 0, 1, List.of(
                new BulkItemResult("a1", BulkItemOutcome.UPDATED, TaskStatus.TODO, TaskStatus.DONE, "au-1", null),
                new BulkItemResult("zz", BulkItemOutcome.FAILED, null, null, null, "NOT_FOUND")));
        when(service.bulkUpdateStatus(eq(LEAD), any(BulkStatusCommand.class))).thenReturn(result);

        mockMvc.perform(patch(URL).header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"activityId\":\"a1\",\"status\":\"DONE\"},"
                                + "{\"activityId\":\"zz\",\"status\":\"BLOCKED\"}],\"note\":\"end of late shift\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value("b-1"))
                .andExpect(jsonPath("$.requested").value(2))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.results[0].outcome").value("UPDATED"))
                .andExpect(jsonPath("$.results[0].status").value("DONE"))
                .andExpect(jsonPath("$.results[0].auditId").value("au-1"))
                .andExpect(jsonPath("$.results[1].outcome").value("FAILED"))
                .andExpect(jsonPath("$.results[1].reason").value("NOT_FOUND"));

        ArgumentCaptor<BulkStatusCommand> command = ArgumentCaptor.forClass(BulkStatusCommand.class);
        verify(service, times(1)).bulkUpdateStatus(eq(LEAD), command.capture());
        assertThat(command.getValue().items()).containsExactly(
                new BulkStatusItem("a1", TaskStatus.DONE), new BulkStatusItem("zz", TaskStatus.BLOCKED));
        assertThat(command.getValue().note()).isEqualTo("end of late shift");
    }

    @Test
    @DisplayName("AC-1.1 bulk update without a bearer token returns 401 UNAUTHORIZED")
    void bulkUpdateRequiresAuthentication() throws Exception {
        mockMvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"activityId\":\"a1\",\"status\":\"DONE\"}]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verify(service, never()).bulkUpdateStatus(any(), any());
    }

    @Test
    @DisplayName("AC-1.2 bulk update with 51 items returns 400 VALIDATION_ERROR and never calls the service")
    void bulkUpdateRejectsMoreThanFiftyItems() throws Exception {
        String items = IntStream.rangeClosed(1, 51)
                .mapToObj(n -> "{\"activityId\":\"a" + n + "\",\"status\":\"DONE\"}")
                .collect(Collectors.joining(","));

        mockMvc.perform(patch(URL).header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[" + items + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.statusCode").value(400));

        verify(service, never()).bulkUpdateStatus(any(), any());
    }

    @Test
    @DisplayName("AC-1.2 bulk update with empty items or an item missing its status returns VALIDATION_ERROR")
    void bulkUpdateRejectsEmptyOrIncompleteItems() throws Exception {
        mockMvc.perform(patch(URL).header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(patch(URL).header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"activityId\":\"a1\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(service, never()).bulkUpdateStatus(any(), any());
    }
}
