package com.tioka.server;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.tioka.server.support.IntegrationTestSupport;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("it")
class OrchAttentionIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }


    @Autowired
    MockMvc mockMvc;

    @Test
    void eventIdempotentAndAttentionLoop() throws Exception {
        // 共享 Spring 上下文与 H2 内存库：用唯一幂等键，避免与其他用例的 run 冲突
        String key = "k1-" + System.nanoTime();
        String run1 = json(mockMvc.perform(post("/api/orch/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"demo\",\"idempotencyKey\":\"" + key + "\"}"))
                .andExpect(status().isOk()).andReturn(), "$.data");
        String run2 = json(mockMvc.perform(post("/api/orch/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"demo\",\"idempotencyKey\":\"" + key + "\"}"))
                .andExpect(status().isOk()).andReturn(), "$.data");
        assertEquals(run1, run2);

        // 其他用例可能留下 READY run，claim 按 FIFO 取，轮询直到领到本用例的 run
        String claimed = null;
        for (int i = 0; i < 32 && claimed == null; i++) {
            MvcResult claim = mockMvc.perform(post("/api/orch/runs/claim")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"workerId\":\"w1-" + i + "\"}"))
                    .andExpect(status().isOk()).andReturn();
            if (run1.equals(json(claim, "$.data.id"))) {
                claimed = run1;
            }
        }
        assertEquals(run1, claimed, "应能 claim 到本用例创建的 run");

        String att = json(mockMvc.perform(post("/api/orch/attentions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"" + run1 + "\",\"title\":\"审批\",\"card\":{\"mode\":\"suspend\"}}"))
                .andExpect(status().isOk()).andReturn(), "$.data.id");

        mockMvc.perform(get("/api/orch/attentions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", hasItem(att)));

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
