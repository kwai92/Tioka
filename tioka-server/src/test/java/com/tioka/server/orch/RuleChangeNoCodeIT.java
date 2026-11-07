package com.tioka.server.orch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.application.AgentService;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateRoutingPlanRequest;
import com.tioka.model.application.ModelAdminService;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 34 号验收：只改规则不改代码即改变路由。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestFixtures.class)
@Tag("it")
class RuleChangeNoCodeIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired TestFixtures fixtures;
    @Autowired ObjectMapper objectMapper;
    @Autowired AgentService agents;
    @Autowired ModelAdminService models;
    @Autowired PrincipalHolder principals;

    @Test
    void changingPublishedRuleChangesDryRunDecision() throws Exception {
        TestFixtures.Actor actor = fixtures.newActor("workspace_admin");
        String agentVersionId = principals.runAs(principal(actor),
                () -> publishAgent(actor.workspaceId(), "nocode"));

        String ruleId = objectMapper.readTree(mockMvc.perform(actor.auth(post("/api/orch/rules"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"route-change\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("id").asText();

        String v1 = objectMapper.readTree(mockMvc.perform(actor.auth(post("/api/orch/rules/{id}/versions", ruleId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(versionBody("attrs.channel == 'feishu'", agentVersionId, actor.userId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("id").asText();

        mockMvc.perform(actor.auth(post("/api/orch/rules/{id}/versions/{vid}/publish", ruleId, v1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"STABLE\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(actor.auth(post("/api/orch/rules/{id}/versions/{vid}/dry-run", ruleId, v1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":{\"type\":\"demo.x\",\"attrs\":{\"channel\":\"feishu\"}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matched").value(true))
                .andExpect(jsonPath("$.data.decision").value("WOULD_ACCEPT"));

        String v2 = objectMapper.readTree(mockMvc.perform(actor.auth(post("/api/orch/rules/{id}/versions", ruleId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(versionBody("attrs.channel == 'web'", agentVersionId, actor.userId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("id").asText();

        mockMvc.perform(actor.auth(post("/api/orch/rules/{id}/versions/{vid}/publish", ruleId, v2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"STABLE\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(actor.auth(post("/api/orch/rules/{id}/versions/{vid}/dry-run", ruleId, v2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":{\"type\":\"demo.x\",\"attrs\":{\"channel\":\"feishu\"}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matched").value(false))
                .andExpect(jsonPath("$.data.decision").value("IGNORE"));
    }

    private static String versionBody(String cel, String agentVersionId, String sa) {
        return """
                {
                  "eventType":"demo.x",
                  "conditionCel":"%s",
                  "actions":[{"type":"agent","agentVersionId":"%s"}],
                  "concurrency":"serial",
                  "acceptMode":"observe",
                  "serviceAccountId":"%s"
                }
                """.formatted(cel, agentVersionId, sa);
    }

    private String publishAgent(String workspaceId, String tag) {
        String planId = publishPlan(tag);
        var agent = agents.create("orch-nocode-" + tag, workspaceId);
        return agents.publishSpec(agent.id(), baseSpec(agent.name(), planId, "hi")).versionId();
    }

    private String publishPlan(String tag) {
        var provider = models.createProvider(new CreateProviderRequest(tag + "-np", "mock://x", "k"));
        String lm = models.createLogicalModel(new CreateLogicalModelRequest(tag + "-nm", "tool"));
        String binding = models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "gpt"));
        String planId = models.createPlan(new CreateRoutingPlanRequest(tag + "-nrp", "FIXED", binding)).id();
        models.publish(planId);
        return planId;
    }

    private static AgentSpec baseSpec(String name, String planId, String text) {
        return new AgentSpec(
                1,
                new AgentSpec.Profile(name, null, null, List.of(), "CHAT"),
                new AgentSpec.Instructions(List.of(new AgentSpec.Instructions.Section("main", text))),
                new AgentSpec.ModelPolicy(planId, false),
                AgentSpec.ToolSet.empty(),
                List.of(),
                AgentSpec.KnowledgeBinding.empty(),
                AgentSpec.SandboxPolicy.defaults(),
                AgentSpec.ApprovalSettings.empty(),
                AgentSpec.Limits.defaults(),
                null, null, null, null);
    }

    private static Principal principal(TestFixtures.Actor actor) {
        return new Principal(actor.userId(), actor.workspaceId(), PrincipalType.USER,
                Set.of("workspace_admin", "system_admin"), Set.of("*"), "password");
    }
}
