package com.tioka.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class OrchAttentionTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void eventIdempotentAndAttentionLoop() throws Exception {
        String run1 = json(mockMvc.perform(post("/api/orch/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"demo\",\"idempotencyKey\":\"k1\"}"))
                .andExpect(status().isOk()).andReturn(), "$.data");
        String run2 = json(mockMvc.perform(post("/api/orch/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"demo\",\"idempotencyKey\":\"k1\"}"))
                .andExpect(status().isOk()).andReturn(), "$.data");
        assertEquals(run1, run2);

        mockMvc.perform(post("/api/orch/runs/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerId\":\"w1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(run1));

        String att = json(mockMvc.perform(post("/api/orch/attentions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"" + run1 + "\",\"title\":\"审批\",\"card\":{\"mode\":\"suspend\"}}"))
                .andExpect(status().isOk()).andReturn(), "$.data.id");

        mockMvc.perform(get("/api/orch/attentions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(att));

        mockMvc.perform(post("/api/orch/attentions/" + att + "/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ANSWERED"));

        mockMvc.perform(get("/api/orch/runs/" + run1 + "/why"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()", greaterThanOrEqualTo(3)));
    }

    private static String json(MvcResult result, String path) throws Exception {
        com.fasterxml.jackson.databind.JsonNode root =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString());
        if ("$.data".equals(path)) {
            return root.path("data").asText();
        }
        if ("$.data.id".equals(path)) {
            return root.path("data").path("id").asText();
        }
        throw new IllegalArgumentException("unsupported path: " + path);
    }
}
