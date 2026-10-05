package com.storeops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end shift handover bulk status update with seeded tokens, real repositories and the real
 * event bus.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ShiftHandoverBulkUpdateIntegrationTest {

    private static final String MANAGER = "Bearer dev-token-manager";
    private static final String LEAD = "Bearer dev-token-lead";
    private static final String OTHER_STORE = "Bearer dev-token-other-store";
    private static final String BULK_URL = "/api/activities/bulk-status";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("AC-2.3 bulk update without a token returns 401 UNAUTHORIZED and leaves the task TODO")
    void bulkUpdateWithoutTokenIsRejected() throws Exception {
        String id = createActivity(MANAGER, "Close-down restock");

        mockMvc.perform(patch(BULK_URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"activityId\":\"" + id + "\",\"status\":\"DONE\"}]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(get("/api/activities/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TODO"));
    }

    @Test
    @DisplayName("AC-2.4 bulk BLOCKED raises exactly one ESCALATION alert for the owner through the real bus")
    void bulkBlockedRaisesEscalationForOwner() throws Exception {
        String x = createActivity(MANAGER, "Chiller audit");
        String y = createActivity(MANAGER, "Aisle 4 planogram");

        mockMvc.perform(patch(BULK_URL).header("Authorization", LEAD).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"activityId\":\"" + x + "\",\"status\":\"BLOCKED\"},"
                                + "{\"activityId\":\"" + y + "\",\"status\":\"DONE\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(2))
                .andExpect(jsonPath("$.failed").value(0));

        MvcResult alerts = mockMvc.perform(get("/api/alerts").header("Authorization", MANAGER))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(alerts.getResponse().getContentAsString());
        assertThat(countEscalations(body, x)).isEqualTo(1);
        assertThat(countEscalations(body, y)).isZero();
    }

    @Test
    @DisplayName("AC-2.5 partial failure leaves the other-store task TODO and audits the updated task")
    void partialFailureAuditsUpdatedTaskOnly() throws Exception {
        String x = createActivity(MANAGER, "Fire exit check");
        String z = createActivity(OTHER_STORE, "Store 2 delivery check");

        mockMvc.perform(patch(BULK_URL).header("Authorization", LEAD).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"activityId\":\"" + x + "\",\"status\":\"DONE\"},"
                                + "{\"activityId\":\"" + z + "\",\"status\":\"DONE\"}],\"note\":\"night handover\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("UPDATED"))
                .andExpect(jsonPath("$.results[1].outcome").value("FAILED"))
                .andExpect(jsonPath("$.results[1].reason").value("NOT_FOUND"));

        mockMvc.perform(get("/api/activities/" + z))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TODO"));
        mockMvc.perform(get("/api/activities/" + x + "/audit").header("Authorization", LEAD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].actorId").value("staff-lead-001"))
                .andExpect(jsonPath("$[0].previousStatus").value("TODO"))
                .andExpect(jsonPath("$[0].newStatus").value("DONE"))
                .andExpect(jsonPath("$[0].note").value("night handover"));
    }

    private String createActivity(String token, String title) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/activities").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("TODO"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private static long countEscalations(JsonNode alerts, String referenceId) {
        long count = 0;
        for (JsonNode alert : alerts) {
            if ("ESCALATION".equals(alert.path("type").asText())
                    && referenceId.equals(alert.path("referenceId").asText())) {
                count++;
            }
        }
        return count;
    }
}
