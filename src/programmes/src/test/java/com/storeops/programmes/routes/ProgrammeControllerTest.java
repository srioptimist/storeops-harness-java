package com.storeops.programmes.routes;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeops.programmes.model.Project;
import com.storeops.programmes.model.ProjectMember;
import com.storeops.programmes.model.ProjectRole;
import com.storeops.programmes.service.ProgrammeService;
import com.storeops.shared.error.ConflictError;
import com.storeops.shared.error.GlobalExceptionHandler;
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

@WebMvcTest(ProgrammeController.class)
@Import({GlobalExceptionHandler.class, StaffWebConfig.class})
class ProgrammeControllerTest {

    private static final String TOKEN = "Bearer t-manager";
    private static final AuthenticatedUser MANAGER =
            new AuthenticatedUser("m1", "store-001", "region-north", StaffRole.STORE_MANAGER);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProgrammeService service;

    @MockitoBean
    private StaffDirectory staffDirectory;

    @BeforeEach
    void authenticate() {
        when(staffDirectory.authenticate("t-manager")).thenReturn(Optional.of(MANAGER));
    }

    @Test
    void listReturnsProgrammesForCallerStore() throws Exception {
        when(service.listForStore(MANAGER)).thenReturn(List.of(project(List.of())));

        mockMvc.perform(get("/api/programmes").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("p1"))
                .andExpect(jsonPath("$[0].storeId").value("store-001"));
    }

    @Test
    void listRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/programmes"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void createReturns201() throws Exception {
        when(service.create(MANAGER, "Refit", "Q4")).thenReturn(project(List.of()));

        mockMvc.perform(post("/api/programmes").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Refit\",\"description\":\"Q4\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Refit"));
    }

    @Test
    void createValidatesName() throws Exception {
        mockMvc.perform(post("/api/programmes").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void addMemberReturns201WithMembers() throws Exception {
        Project withMember = project(List.of(new ProjectMember("s1", ProjectRole.ASSOCIATE, Instant.now())));
        when(service.addMember(MANAGER, "p1", "s1", ProjectRole.ASSOCIATE)).thenReturn(withMember);

        mockMvc.perform(post("/api/programmes/p1/members").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"staffId\":\"s1\",\"role\":\"ASSOCIATE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.members[0].staffId").value("s1"))
                .andExpect(jsonPath("$.members[0].role").value("ASSOCIATE"));
    }

    @Test
    void addMemberRejectsUnknownRole() throws Exception {
        mockMvc.perform(post("/api/programmes/p1/members").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"staffId\":\"s1\",\"role\":\"CEO\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addMemberPropagatesServiceErrors() throws Exception {
        when(service.addMember(eq(MANAGER), eq("p1"), eq("s1"), any())).thenThrow(new ConflictError("dup"));

        mockMvc.perform(post("/api/programmes/p1/members").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"staffId\":\"s1\",\"role\":\"ASSOCIATE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    private static Project project(List<ProjectMember> members) {
        return new Project("p1", "store-001", "Refit", "Q4", "m1", members, Instant.parse("2026-09-01T00:00:00Z"));
    }
}
