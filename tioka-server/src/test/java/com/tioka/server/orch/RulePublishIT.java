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
import com.tioka.orchestration.infrastructure.OrchRuleRepository;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleVersionDO;
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
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestFixtures.class)
@Tag("it")
class RulePublishIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        registry.add("tioka.messaging.mode", () -> "in-memory");
    }

    @Autowired MockMvc mockMvc;
    @Autowired TestFixtures fixtures;
    @Autowired ObjectMapper objectMapper;
    @Autowired AgentService agents;
    @Autowired ModelAdminService models;
    @Autowired PrincipalHolder principals;
    @Autowired OrchRuleRepository ruleRepo;

    @Test
    void badCelRejected() throws Exception {
        TestFixtures.Actor actor = fixtures.newActor("workspace_admin");
        String agentVersionId = principals.runAs(principal(actor), () -> publishAgent(actor.workspaceId(), "badcel"));

        MvcResult ruleRes = mockMvc.perform(actor.auth(post("/api/orch/rules"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bad-cel\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String ruleId = objectMapper.readTree(ruleRes.getResponse().getContentAsString())
                .path("data").path("id").asText();

        mockMvc.perform(actor.auth(post("/api/orch/rules/{id}/versions", ruleId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventType":"demo.event",
                                  "conditionCel":"this is not ((( cel",
                                  "actions":[{"type":"agent","agentVersionId":"%s"}],
                                  "concurrency":"serial",
                                  "acceptMode":"observe",
                                  "serviceAccountId":"%s"
                                }
                                """.formatted(agentVersionId, actor.userId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void flowActionRejectedOnPublish() throws Exception {
        TestFixtures.Actor actor = fixtures.newActor("workspace_admin");
        MvcResult ruleRes = mockMvc.perform(actor.auth(post("/api/orch/rules"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"flow-reject\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String ruleId = objectMapper.readTree(ruleRes.getResponse().getContentAsString())
                .path("data").path("id").asText();

        mockMvc.perform(actor.auth(post("/api/orch/rules/{id}/versions", ruleId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventType":"demo.event",
                                  "conditionCel":"true",
                                  "actions":[{"type":"flow","flowPublicationId":"fp_x"}],
                                  "concurrency":"serial",
                                  "acceptMode":"auto",
                                  "serviceAccountId":"%s"
                                }
                                """.formatted(actor.userId())))
                .andExpect(status().isBadRequest())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertTrue(body.contains("flow") || body.contains("F23"));
                });
    }

    @Test
    void publishStableSucceeds() throws Exception {
        TestFixtures.Actor actor = fixtures.newActor("workspace_admin");
        String agentVersionId = principals.runAs(principal(actor), () -> publishAgent(actor.workspaceId(), "ok"));

        String ruleId = objectMapper.readTree(mockMvc.perform(actor.auth(post("/api/orch/rules"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ok-rule\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("id").asText();

        String versionId = objectMapper.readTree(mockMvc.perform(actor.auth(post("/api/orch/rules/{id}/versions", ruleId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventType":"demo.ok",
                                  "conditionCel":"attrs.channel == 'feishu'",
                                  "actions":[{"type":"agent","agentVersionId":"%s"}],
                                  "concurrency":"serial",
                                  "acceptMode":"observe",
                                  "serviceAccountId":"%s"
                                }
                                """.formatted(agentVersionId, actor.userId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("id").asText();

        mockMvc.perform(actor.auth(post("/api/orch/rules/{id}/versions/{vid}/publish", ruleId, versionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"STABLE\",\"publishNote\":\"first\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("STABLE"))
                .andExpect(jsonPath("$.data.contentHash").isNotEmpty());

        OrchRuleVersionDO stored = ruleRepo.findVersion(versionId);
        assertNotNull(stored);
        assertNotNull(stored.getContentHash());
        assertFalse(stored.getContentHash().isBlank());
        assertEquals("first", stored.getPublishNote());
        assertNull(stored.getPreviousVersionId());
    }

    @Test
    void publishPersistsF17ColumnsWithPreviousVersion() throws Exception {
        TestFixtures.Actor actor = fixtures.newActor("workspace_admin");
        String agentVersionId = principals.runAs(principal(actor), () -> publishAgent(actor.workspaceId(), "f17"));

        String ruleId = objectMapper.readTree(mockMvc.perform(actor.auth(post("/api/orch/rules"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"f17-rule\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("id").asText();

        String v1Id = createVersion(actor, ruleId, agentVersionId, "demo.f17.a");
        mockMvc.perform(actor.auth(post("/api/orch/rules/{id}/versions/{vid}/publish", ruleId, v1Id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"STABLE\",\"publishNote\":\"v1\"}"))
                .andExpect(status().isOk());

        String v2Id = createVersion(actor, ruleId, agentVersionId, "demo.f17.b");
        mockMvc.perform(actor.auth(post("/api/orch/rules/{id}/versions/{vid}/publish", ruleId, v2Id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"STABLE\",\"publishNote\":\"v2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previousEffectiveVersionId").value(v1Id));

        OrchRuleVersionDO v1 = ruleRepo.findVersion(v1Id);
        OrchRuleVersionDO v2 = ruleRepo.findVersion(v2Id);
        assertNotNull(v1.getContentHash());
        assertFalse(v1.getContentHash().isBlank());
        assertEquals("v1", v1.getPublishNote());
        assertNull(v1.getPreviousVersionId());

        assertNotNull(v2.getContentHash());
        assertFalse(v2.getContentHash().isBlank());
        assertEquals(64, v2.getContentHash().length());
        assertEquals("v2", v2.getPublishNote());
        assertEquals(v1Id, v2.getPreviousVersionId());
    }

    private String createVersion(TestFixtures.Actor actor, String ruleId, String agentVersionId, String eventType)
            throws Exception {
        return objectMapper.readTree(mockMvc.perform(actor.auth(post("/api/orch/rules/{id}/versions", ruleId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventType":"%s",
                                  "conditionCel":"true",
                                  "actions":[{"type":"agent","agentVersionId":"%s"}],
                                  "concurrency":"serial",
                                  "acceptMode":"observe",
                                  "serviceAccountId":"%s"
                                }
                                """.formatted(eventType, agentVersionId, actor.userId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("id").asText();
    }

    private String publishAgent(String workspaceId, String tag) {
        String planId = publishPlan(tag);
        var agent = agents.create("orch-rule-agent-" + tag, workspaceId);
        return agents.publishSpec(agent.id(), baseSpec(agent.name(), planId, "hi")).versionId();
    }

    private String publishPlan(String tag) {
        var provider = models.createProvider(new CreateProviderRequest(tag + "-p", "mock://x", "k"));
        String lm = models.createLogicalModel(new CreateLogicalModelRequest(tag + "-m", "tool"));
        String binding = models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "gpt"));
        String planId = models.createPlan(new CreateRoutingPlanRequest(tag + "-rp", "FIXED", binding)).id();
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
