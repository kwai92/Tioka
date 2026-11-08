package com.tioka.server.chat;

import com.jayway.jsonpath.JsonPath;
import com.tioka.server.gate.support.GateSeed;
import com.tioka.server.gate.support.LlmStubServer;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CHT-02：Chat 发送异步化——不等模型；clientMsgId 幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("it")
class ChatAsyncIT {

    private static LlmStubServer llm;
    private static String previousScript;

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @BeforeAll
    static void startLlm() {
        previousScript = System.getProperty("tioka.llm.script");
        System.setProperty("tioka.llm.script", "chat-async-delay");
        llm = LlmStubServer.start();
        try {
            llm.loadClasspath("llm-scripts/chat-async-delay.yaml", "chat-async-delay");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stopLlm() {
        if (llm != null) {
            llm.close();
        }
        if (previousScript == null) {
            System.clearProperty("tioka.llm.script");
        } else {
            System.setProperty("tioka.llm.script", previousScript);
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired ConfigurableApplicationContext ctx;

    private GateSeed.Bundle bundle;

    @BeforeEach
    void seed() {
        bundle = GateSeed.seedPublishedAgent(ctx, llm.baseUrl());
    }

    @Test
    void sendReturnsBeforeModel() throws Exception {
        String convId = createConversation("async-latency");

        long t0 = System.nanoTime();
        MvcResult result = mockMvc.perform(auth(post("/api/chat/conversations/" + convId + "/messages"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hello async\",\"clientMsgId\":\"cm-async-1\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.turnId").isNotEmpty())
                .andExpect(jsonPath("$.data.threadId").isNotEmpty())
                .andExpect(jsonPath("$.data.eventsUrl").isNotEmpty())
                .andReturn();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(elapsedMs < 200, "应在模型桩 5s 延迟前返回, elapsedMs=" + elapsedMs);

        String threadId = JsonPath.read(result.getResponse().getContentAsString(), "$.data.threadId");
        mockMvc.perform(auth(get("/api/chat/conversations/" + convId + "/messages"))
                        .param("afterSeq", "0")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].threadId").value(threadId))
                .andExpect(jsonPath("$.data[0].role").value("user"));
    }

    @Test
    void idempotentClientMsgId() throws Exception {
        String convId = createConversation("async-idem");

        MvcResult first = mockMvc.perform(auth(post("/api/chat/conversations/" + convId + "/messages"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"same\",\"clientMsgId\":\"cm-idem-1\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        String turn1 = JsonPath.read(first.getResponse().getContentAsString(), "$.data.turnId");

        MvcResult second = mockMvc.perform(auth(post("/api/chat/conversations/" + convId + "/messages"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"same\",\"clientMsgId\":\"cm-idem-1\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        String turn2 = JsonPath.read(second.getResponse().getContentAsString(), "$.data.turnId");
        assertEquals(turn1, turn2);
    }

    private String createConversation(String title) throws Exception {
        MvcResult created = mockMvc.perform(auth(post("/api/chat/conversations"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"agentVersionId\":\"" + bundle.agentVersionId() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.threadId").isNotEmpty())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder auth(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b) {
        return b.header("Authorization", "Bearer " + bundle.token())
                .header("X-Workspace-Id", bundle.workspaceId());
    }
}
