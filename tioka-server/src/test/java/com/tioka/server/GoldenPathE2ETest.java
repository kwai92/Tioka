package com.tioka.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W1-26 黄金路径：登录 → 模型/Agent/MCP → 对话（含工具）→ 流式回复。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class GoldenPathE2ETest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void loginModelAgentMcpChat() throws Exception {
        mockMvc.perform(post("/api/system/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty());

        String providerId = json(mockMvc.perform(post("/api/model/providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"mock\",\"baseUrl\":\"mock://openai\",\"apiKeyPlain\":\"sk-test\"}"))
                .andExpect(status().isOk())
                .andReturn(), "$.data.id");

        String logicalModelId = json(mockMvc.perform(post("/api/model/logical-models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"gpt-mock\",\"capabilityTags\":\"tool\"}"))
                .andExpect(status().isOk())
                .andReturn(), "$.data");

        String bindingId = json(mockMvc.perform(post("/api/model/bindings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"logicalModelId\":\"" + logicalModelId + "\",\"providerId\":\"" + providerId + "\",\"modelName\":\"gpt-mock\"}"))
                .andExpect(status().isOk())
                .andReturn(), "$.data");

        String planId = json(mockMvc.perform(post("/api/model/routing-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"fixed\",\"type\":\"FIXED\",\"primaryBindingId\":\"" + bindingId + "\"}"))
                .andExpect(status().isOk())
                .andReturn(), "$.data.id");

        mockMvc.perform(post("/api/model/routing-plans/" + planId + "/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.published").value(true));

        String agentId = json(mockMvc.perform(post("/api/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"PoC Agent\",\"workspaceId\":\"ws_default\"}"))
                .andExpect(status().isOk())
                .andReturn(), "$.data.id");

        String serverId = json(mockMvc.perform(post("/api/mcp/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"mock-mcp\",\"baseUrl\":\"mock://mcp\",\"bearerToken\":\"tok\"}"))
                .andExpect(status().isOk())
                .andReturn(), "$.data.id");

        mockMvc.perform(post("/api/mcp/servers/" + serverId + "/refresh-tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("mcp_echo"));

        mockMvc.perform(post("/api/mcp/grants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + agentId + "\",\"serverId\":\"" + serverId + "\",\"allowlist\":[\"mcp_echo\"]}"))
                .andExpect(status().isOk());

        String versionId = json(mockMvc.perform(post("/api/agents/" + agentId + "/drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instructions\":\"you are helpful\",\"routingPlanId\":\"" + planId + "\",\"mcpGrantIds\":[]}"))
                .andExpect(status().isOk())
                .andReturn(), "$.data.id");

        mockMvc.perform(post("/api/agents/versions/" + versionId + "/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.published").value(true))
                .andExpect(jsonPath("$.data.snapshotHash").isNotEmpty());

        String convId = json(mockMvc.perform(post("/api/chat/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"poc\",\"agentVersionId\":\"" + versionId + "\"}"))
                .andExpect(status().isOk())
                .andReturn(), "$.data.id");

        mockMvc.perform(post("/api/chat/conversations/" + convId + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"please echo hello world\",\"clientMsgId\":\"c1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("assistant"))
                .andExpect(jsonPath("$.data.text", containsString("hello")));

        mockMvc.perform(get("/api/chat/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(convId));
    }

    private static String json(MvcResult result, String path) throws Exception {
        String body = result.getResponse().getContentAsString();
        com.jayway.jsonpath.JsonPath compiled = com.jayway.jsonpath.JsonPath.compile(path);
        Object val = compiled.read(body);
        return String.valueOf(val);
    }
}
