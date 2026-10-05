package com.storeops;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Full-stack test over the 9 base endpoints (Section 3.6) with real in-memory repositories and
 * the real event bus. Uses the seeded development tokens from the staff module.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class StoreOpsApiIntegrationTest {

    private static final String MANAGER = "Bearer dev-token-manager";
    private static final String LEAD = "Bearer dev-token-lead";
    private static final String ASSOCIATE = "Bearer dev-token-associate";
    private static final String OTHER_STORE = "Bearer dev-token-other-store";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void baselineSmokeCheckTasksEndpointReturns200() throws Exception {
        mockMvc.perform(get("/api/tasks")).andExpect(status().isOk());
    }

    @Test
    void programmeLifecycle() throws Exception {
        String programmeId = createProgramme(MANAGER, "Autumn planogram reset");

        mockMvc.perform(get("/api/programmes").header("Authorization", MANAGER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(programmeId));
        mockMvc.perform(get("/api/programmes").header("Authorization", OTHER_STORE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(post("/api/programmes/" + programmeId + "/members").header("Authorization", MANAGER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"staffId\":\"staff-assoc-001\",\"role\":\"ASSOCIATE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.members[0].staffId").value("staff-assoc-001"));

        mockMvc.perform(post("/api/programmes/" + programmeId + "/members").header("Authorization", MANAGER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"staffId\":\"staff-assoc-001\",\"role\":\"ASSOCIATE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void activityLifecycleRaisesAlertsViaEventBus() throws Exception {
        String programmeId = createProgramme(MANAGER, "Compliance drive");

        MvcResult created = mockMvc.perform(post("/api/activities").header("Authorization", LEAD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Fire exit audit\",\"priority\":\"CRITICAL\",\"category\":\"COMPLIANCE\","
                                + "\"programmeId\":\"" + programmeId + "\",\"assigneeId\":\"staff-assoc-001\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("TODO"))
                .andExpect(jsonPath("$.ownerId").value("staff-lead-001"))
                .andReturn();
        String activityId = idOf(created);

        mockMvc.perform(get("/api/activities").param("programmeId", programmeId).param("status", "TODO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(activityId)));
        mockMvc.perform(get("/api/activities/" + activityId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Fire exit audit"));

        // Assignment -> SHIFT_HANDOVER alert for the assignee (via event bus).
        mockMvc.perform(get("/api/alerts").header("Authorization", ASSOCIATE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("SHIFT_HANDOVER"))
                .andExpect(jsonPath("$[0].referenceId").value(activityId));

        // BLOCKED -> ESCALATION alert for the owner (via event bus).
        mockMvc.perform(patch("/api/activities/" + activityId).header("Authorization", ASSOCIATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BLOCKED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"));
        mockMvc.perform(get("/api/alerts").header("Authorization", LEAD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("ESCALATION"));

        // Only owner or store manager may delete.
        mockMvc.perform(delete("/api/activities/" + activityId).header("Authorization", ASSOCIATE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(delete("/api/activities/" + activityId).header("Authorization", MANAGER))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/activities/" + activityId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void errorsUseTypedErrorContract() throws Exception {
        mockMvc.perform(post("/api/activities").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.statusCode").value(401));
        mockMvc.perform(post("/api/activities").header("Authorization", LEAD)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\",\"assigneeId\":\"ghost\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private String createProgramme(String token, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/programmes").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(result);
    }

    private String idOf(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("id").asText();
    }
}
