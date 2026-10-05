package com.storeops.activities.routes;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeops.activities.model.Task;
import com.storeops.activities.model.TaskCategory;
import com.storeops.activities.model.TaskPriority;
import com.storeops.activities.model.TaskStatus;
import com.storeops.activities.service.ActivityService;
import com.storeops.activities.service.NewTask;
import com.storeops.activities.service.TaskChanges;
import com.storeops.shared.error.ForbiddenError;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ActivityController.class)
@Import({GlobalExceptionHandler.class, StaffWebConfig.class})
class ActivityControllerTest {

    private static final String TOKEN = "Bearer t-lead";
    private static final AuthenticatedUser LEAD =
            new AuthenticatedUser("lead", "store-001", "region-north", StaffRole.DEPARTMENT_LEAD);

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
    void listPassesFiltersToService() throws Exception {
        when(service.list("prog-1", TaskStatus.TODO)).thenReturn(List.of(task()));

        mockMvc.perform(get("/api/activities").param("programmeId", "prog-1").param("status", "TODO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("t1"))
                .andExpect(jsonPath("$[0].status").value("TODO"));
    }

    @Test
    void tasksAliasListsActivities() throws Exception {
        when(service.list(null, null)).thenReturn(List.of());

        mockMvc.perform(get("/api/tasks")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
    }

    @Test
    void listRejectsUnknownStatus() throws Exception {
        mockMvc.perform(get("/api/activities").param("status", "SLEEPING"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void createReturns201() throws Exception {
        when(service.create(eq(LEAD), any(NewTask.class))).thenReturn(task());

        mockMvc.perform(post("/api/activities").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Restock\",\"priority\":\"HIGH\",\"category\":\"RESTOCKING\","
                                + "\"dueDate\":\"2026-10-01\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Restock"));
    }

    @Test
    void createRequiresAuthAndTitle() throws Exception {
        mockMvc.perform(post("/api/activities").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/activities").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void getReturnsActivityOr404() throws Exception {
        when(service.get("t1")).thenReturn(task());
        when(service.get("missing")).thenThrow(new NotFoundError("Activity", "missing"));

        mockMvc.perform(get("/api/activities/t1")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value("t1"));
        mockMvc.perform(get("/api/activities/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.statusCode").value(404));
    }

    @Test
    void patchMapsBodyToChanges() throws Exception {
        TaskChanges changes = new TaskChanges(TaskStatus.DONE, null, null, "assoc");
        when(service.update(LEAD, "t1", changes)).thenReturn(task());

        mockMvc.perform(patch("/api/activities/t1").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\",\"assigneeId\":\"assoc\"}"))
                .andExpect(status().isOk());
        verify(service).update(LEAD, "t1", changes);
    }

    @Test
    void deleteReturns204OrForbidden() throws Exception {
        mockMvc.perform(delete("/api/activities/t1").header("Authorization", TOKEN))
                .andExpect(status().isNoContent());
        verify(service).delete(LEAD, "t1");

        doThrow(new ForbiddenError("nope")).when(service).delete(LEAD, "t2");
        mockMvc.perform(delete("/api/activities/t2").header("Authorization", TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private static Task task() {
        Instant now = Instant.parse("2026-09-30T09:00:00Z");
        return new Task("t1", "store-001", null, "Restock", null, TaskStatus.TODO, TaskPriority.HIGH,
                TaskCategory.RESTOCKING, null, "lead", null, now, now);
    }
}
