package com.tioka.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W5-15：G1–G7 自动门禁回归（相对浅冒烟加强行为断言）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class GateRegressionSuiteTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    MockMvc mockMvc;

    @Test
    void g1_runtimeSteerCancelAndTools() throws Exception {
        Fixture f = bootstrapModelAgent();
        String sess = text(postJson("/api/runtime/sessions",
                "{\"agentVersionId\":\"" + f.versionId + "\",\"routingPlanId\":\"" + f.planId + "\"}"), "data", "id");

        mockMvc.perform(post("/api/runtime/sessions/" + sess + "/steer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"be concise\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/runtime/sessions/" + sess + "/append")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"please echo gate-g1\",\"clientMsgId\":\"g1a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parts[?(@.type=='tool_result')].text").isNotEmpty());

        mockMvc.perform(post("/api/runtime/sessions/" + sess + "/cancel"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/runtime/sessions/" + sess + "/append")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"should fail\",\"clientMsgId\":\"g1b\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void g1b_freezeDifficultyFailoverCost() throws Exception {
        String p1 = text(postJson("/api/model/providers",
                "{\"name\":\"p1\",\"baseUrl\":\"mock://openai\",\"apiKeyPlain\":\"k1\"}"), "data", "id");
        String p2 = text(postJson("/api/model/providers",
                "{\"name\":\"p2\",\"baseUrl\":\"mock://openai\",\"apiKeyPlain\":\"k2\"}"), "data", "id");
        String lm = text(postJson("/api/model/logical-models",
                "{\"name\":\"lm\",\"capabilityTags\":\"tool\"}"), "data");
        String easy = text(postJson("/api/model/bindings",
                "{\"logicalModelId\":\"" + lm + "\",\"providerId\":\"" + p1 + "\",\"modelName\":\"easy\"}"), "data");
        String hard = text(postJson("/api/model/bindings",
                "{\"logicalModelId\":\"" + lm + "\",\"providerId\":\"" + p1 + "\",\"modelName\":\"hard\"}"), "data");
        String fallback = text(postJson("/api/model/bindings",
                "{\"logicalModelId\":\"" + lm + "\",\"providerId\":\"" + p2 + "\",\"modelName\":\"fb\"}"), "data");
        String plan = text(postJson("/api/model/routing-plans",
                "{\"name\":\"diff\",\"type\":\"DIFFICULTY\",\"primaryBindingId\":\"" + easy
                        + "\",\"hardBindingId\":\"" + hard + "\",\"fallbackBindingId\":\"" + fallback + "\"}"),
                "data", "id");
        mockMvc.perform(post("/api/model/routing-plans/" + plan + "/publish"))
                .andExpect(jsonPath("$.data.published").value(true));

        String s1 = text(postJson("/api/model/router/select",
                "{\"sessionId\":\"sess-diff-1\",\"routingPlanId\":\"" + plan + "\",\"userText\":\"hello\"}"),
                "data", "bindingId");
        assertEquals(easy, s1);
        String s1b = text(postJson("/api/model/router/select",
                "{\"sessionId\":\"sess-diff-1\",\"routingPlanId\":\"" + plan + "\",\"userText\":\"hard 复杂问题\"}"),
                "data", "bindingId");
        assertEquals(easy, s1b, "同轮冻结不得因二次文本换模");

        String s2 = text(postJson("/api/model/router/select",
                "{\"sessionId\":\"sess-diff-2\",\"routingPlanId\":\"" + plan + "\",\"userText\":\"这是 hard 复杂任务\"}"),
                "data", "bindingId");
        assertEquals(hard, s2);

        String afterFail = text(postJson("/api/model/router/failover",
                "{\"sessionId\":\"sess-diff-2\",\"routingPlanId\":\"" + plan + "\",\"reason\":\"provider_5xx\"}"),
                "data", "bindingId");
        assertEquals(fallback, afterFail);

        mockMvc.perform(get("/api/runtime/cost/sessions/sess-diff-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.reason=='failover')]").isNotEmpty());
    }

    @Test
    void g2_dualCardsImAndFork() throws Exception {
        Fixture f = bootstrapModelAgent();
        String conv = text(postJson("/api/chat/conversations",
                "{\"title\":\"g2\",\"agentVersionId\":\"" + f.versionId + "\"}"), "data", "id");

        MvcResult continueEmit = mockMvc.perform(post("/api/chat/cards/continue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"" + conv + "\",\"prompt\":\"继续吗？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parts[0].type").value("interactive_card"))
                .andExpect(jsonPath("$.data.parts[0].payload.copy").value("请选择后续"))
                .andExpect(jsonPath("$.data.parts[0].payload.cta").value("提交续答"))
                .andReturn();
        String continueMsgId = MAPPER.readTree(continueEmit.getResponse().getContentAsString())
                .path("data").path("id").asText();

        mockMvc.perform(post("/api/chat/cards/continue/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"" + conv + "\",\"messageIdOrPartHint\":\""
                                + continueMsgId + "\",\"answerText\":\"好的，继续\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("user"))
                .andExpect(jsonPath("$.data.text").value("好的，继续"));

        String run = text(postJson("/api/orch/events",
                "{\"eventType\":\"g2\",\"idempotencyKey\":\"g2-" + System.nanoTime() + "\"}"), "data");
        MvcResult approvalEmit = mockMvc.perform(post("/api/chat/cards/approval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"" + conv + "\",\"runId\":\"" + run + "\",\"title\":\"审批\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parts[0].type").value("approval_card"))
                .andExpect(jsonPath("$.data.parts[0].payload.copy").value("需审批后继续"))
                .andExpect(jsonPath("$.data.parts[0].payload.attentionId").isNotEmpty())
                .andReturn();
        String attentionId = MAPPER.readTree(approvalEmit.getResponse().getContentAsString())
                .path("data").path("parts").get(0).path("payload").path("attentionId").asText();

        mockMvc.perform(get("/api/orch/attentions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id=='" + attentionId + "')]").isNotEmpty());

        mockMvc.perform(post("/api/chat/cards/approval/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"" + conv + "\",\"attentionId\":\""
                                + attentionId + "\",\"approved\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("assistant"))
                .andExpect(jsonPath("$.data.text").value("已批准续跑"));

        mockMvc.perform(get("/api/orch/attentions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id=='" + attentionId + "')]").isEmpty());

        mockMvc.perform(post("/api/connector/im/feishu/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"oc_g2\",\"text\":\"hello im\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/connector/im/feishu/outbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].text").value("hello im"));

        mockMvc.perform(post("/api/ops/chat/" + conv + "/fork"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isNotEmpty());
    }

    @Test
    void g3_knowledgeHybridCitationEvalAndSources() throws Exception {
        String kb = text(postJson("/api/knowledge/bases", "{\"name\":\"kb-g3\"}"), "data", "id");
        mockMvc.perform(post("/api/knowledge/bases/" + kb + "/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"md\",\"content\":\"# Title\\nretrieval citation vector\",\"sourceType\":\"upload\",\"format\":\"md\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/knowledge/bases/" + kb + "/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"html\",\"content\":\"<p>another retrieval chunk</p>\",\"sourceType\":\"web\",\"format\":\"html\"}"))
                .andExpect(status().isOk());

        MvcResult search = mockMvc.perform(post("/api/knowledge/bases/" + kb + "/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"retrieval\",\"topK\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].citationId").isNotEmpty())
                .andExpect(jsonPath("$.data[0].score").isNumber())
                .andReturn();
        String citationId = MAPPER.readTree(search.getResponse().getContentAsString())
                .path("data").get(0).path("citationId").asText();
        mockMvc.perform(get("/api/knowledge/citations/" + citationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chunkId").isNotEmpty())
                .andExpect(jsonPath("$.data.text", containsString("retrieval")));

        for (String type : new String[]{"upload", "feishu", "web"}) {
            mockMvc.perform(post("/api/ops/datasources")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"kbId\":\"" + kb + "\",\"type\":\"" + type + "\",\"uri\":\"" + type + "://x\"}"))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/knowledge/eval/" + kb)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"query\":\"retrieval\",\"expectContains\":\"retrieval\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hit", greaterThanOrEqualTo(1)));
    }

    @Test
    void g4_orchAttentionWhyLater() throws Exception {
        String run = text(postJson("/api/orch/events",
                "{\"eventType\":\"g4\",\"idempotencyKey\":\"g4-" + System.nanoTime() + "\"}"), "data");
        // 共享 Spring 上下文下可能有历史 READY run，轮询 claim 直到命中本用例
        String claimed = null;
        for (int i = 0; i < 32; i++) {
            MvcResult claim = mockMvc.perform(post("/api/orch/runs/claim")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"workerId\":\"w-g4-" + i + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode data = MAPPER.readTree(claim.getResponse().getContentAsString()).path("data");
            if (data == null || data.isNull() || data.isMissingNode()) {
                continue;
            }
            String id = data.path("id").asText();
            if (run.equals(id)) {
                claimed = id;
                break;
            }
        }
        assertEquals(run, claimed, "应能 claim 到本用例创建的 run");
        String att = text(postJson("/api/orch/attentions",
                "{\"runId\":\"" + run + "\",\"title\":\"挂起\",\"card\":{\"mode\":\"suspend\"}}"), "data", "id");
        mockMvc.perform(post("/api/orch/attentions/" + att + "/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}"))
                .andExpect(jsonPath("$.data.status").value("ANSWERED"));
        mockMvc.perform(get("/api/orch/runs/" + run + "/why"))
                .andExpect(jsonPath("$.data.length()", greaterThanOrEqualTo(3)));
        mockMvc.perform(post("/api/orch/later")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delayMs\":1,\"eventType\":\"later-g4\",\"idempotencyKey\":\"later-" + System.nanoTime() + "\"}"))
                .andExpect(status().isOk());
        Thread.sleep(50);
        mockMvc.perform(post("/api/orch/runs/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerId\":\"w-g4b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNotEmpty());
    }

    @Test
    void g5_flowNodeCatalogBranchAndPublication() throws Exception {
        mockMvc.perform(get("/api/flows/node-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasItems("IO", "LLM", "Agent", "Retrieve", "HTTP", "MCP", "Branch", "HumanCard")));

        String draft = text(postJson("/api/flows/drafts", """
                {"name":"branch-flow","graph":{
                  "nodes":[
                    {"id":"n1","type":"IO"},
                    {"id":"n2","type":"Branch","config":{"field":"route","equals":"A"}},
                    {"id":"n3","type":"LLM"},
                    {"id":"n4","type":"HTTP"}
                  ],
                  "edges":[
                    {"from":"n1","to":"n2"},
                    {"from":"n2","to":"n3","when":"true"},
                    {"from":"n2","to":"n4","when":"false"}
                  ]
                }}
                """), "data", "id");
        String ver = text(postJson("/api/flows/drafts/" + draft + "/publish", "{}"), "data", "id");
        mockMvc.perform(post("/api/flows/versions/" + ver + "/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"route\":\"A\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.trace[?(@.type=='LLM')]").isNotEmpty())
                .andExpect(jsonPath("$.data.trace[?(@.type=='HTTP')]").isEmpty());

        mockMvc.perform(post("/api/flows/publications/tools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionId\":\"" + ver + "\",\"toolName\":\"flow_g5\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/flows/publications/internal/" + ver)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"route\":\"A\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        String webhookBody = "{\"route\":\"A\"}";
        String sig = "sha256=" + com.tioka.flow.application.FlowPublicationService.hmacSha256Hex(
                "gate-test-secret", webhookBody);
        mockMvc.perform(post("/api/flows/publications/webhook/" + ver)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tioka-Signature", sig)
                        .content(webhookBody))
                .andExpect(status().isOk());
    }

    @Test
    void g6_catalogQuotaAuditEnv() throws Exception {
        mockMvc.perform(get("/api/catalog?q=agent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(post("/api/system/quota/workspaces/ws_q/limits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tokenLimit\":5,\"concurrentLimit\":2}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/system/quota/workspaces/ws_q/consume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tokens\":3}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/system/quota/workspaces/ws_q/consume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tokens\":3}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42901));

        mockMvc.perform(get("/api/ops/audit/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(get("/api/ops/env"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").isNotEmpty());
    }

    @Test
    void g7_enterpriseAdaptersAirgapScim() throws Exception {
        mockMvc.perform(get("/api/ops/adapters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.im", hasItems("feishu", "dingtalk", "wecom")))
                .andExpect(jsonPath("$.data.biz", hasItem("example")));

        mockMvc.perform(post("/api/ops/scim/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"externalId\":\"u1\",\"userName\":\"alice\",\"action\":\"upsert\"}]"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/ops/airgap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").isBoolean());

        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    private Fixture bootstrapModelAgent() throws Exception {
        String providerId = text(postJson("/api/model/providers",
                "{\"name\":\"m\",\"baseUrl\":\"mock://openai\",\"apiKeyPlain\":\"k\"}"), "data", "id");
        String lm = text(postJson("/api/model/logical-models",
                "{\"name\":\"lm\",\"capabilityTags\":\"tool\"}"), "data");
        String binding = text(postJson("/api/model/bindings",
                "{\"logicalModelId\":\"" + lm + "\",\"providerId\":\"" + providerId + "\",\"modelName\":\"m1\"}"), "data");
        String plan = text(postJson("/api/model/routing-plans",
                "{\"name\":\"p\",\"type\":\"FIXED\",\"primaryBindingId\":\"" + binding + "\"}"), "data", "id");
        mockMvc.perform(post("/api/model/routing-plans/" + plan + "/publish"))
                .andExpect(jsonPath("$.data.published").value(true));
        String agentId = text(postJson("/api/agents",
                "{\"name\":\"Gate Agent\",\"workspaceId\":\"ws_default\"}"), "data", "id");
        String versionId = text(postJson("/api/agents/" + agentId + "/drafts",
                "{\"instructions\":\"helpful\",\"routingPlanId\":\"" + plan + "\",\"mcpGrantIds\":[]}"), "data", "id");
        mockMvc.perform(post("/api/agents/versions/" + versionId + "/publish"))
                .andExpect(jsonPath("$.data.published").value(true));
        return new Fixture(plan, versionId);
    }

    private MvcResult postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String text(MvcResult result, String... path) throws Exception {
        JsonNode node = MAPPER.readTree(result.getResponse().getContentAsString());
        for (String p : path) {
            node = node.path(p);
        }
        return node.asText();
    }

    private record Fixture(String planId, String versionId) {}
}
