package com.tioka.server.orch;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import com.tioka.orchestration.api.engine.OrchApi;
import com.tioka.orchestration.infrastructure.mapper.OrchRunMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchWorkItemMapper;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import com.tioka.orchestration.infrastructure.persistence.OrchWorkItemDO;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ORC-03 归单并发模式：serial / merge / parallel。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestFixtures.class)
@Tag("it")
class OrchWorkItemIT {

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
    @Autowired OrchApi orchApi;
    @Autowired OrchWorkItemMapper workItems;
    @Autowired OrchRunMapper runs;

    @Test
    void serialQueues() throws Exception {
        Fixture fx = seedRule("serial", "ser");
        String subject = "serial-subj";
        principals.runAs(principal(fx.actor), () -> {
            orchApi.ingest(env(fx, subject, "e1"));
            orchApi.ingest(env(fx, subject, "e2"));
            OrchWorkItemDO wi = workItems.findByRuleAndSubject(fx.ruleId, subject);
            assertNotNull(wi);
            assertNotNull(wi.getActiveRunId());
            List<OrchRunDO> runList = runs.selectList(Wrappers.<OrchRunDO>lambdaQuery()
                    .eq(OrchRunDO::getWorkItemId, wi.getId())
                    .orderByAsc(OrchRunDO::getCreateAt));
            assertEquals(2, runList.size());
            assertEquals(wi.getActiveRunId(), runList.get(0).getId());
            assertEquals(OrchApi.RunStatus.PENDING.name(), runList.get(1).getStatus());
            return null;
        });
    }

    @Test
    void mergeCombinesPendingEvents() throws Exception {
        Fixture fx = seedRule("merge", "mrg");
        String subject = "merge-subj";
        principals.runAs(principal(fx.actor), () -> {
            orchApi.ingest(env(fx, subject, "m1"));
            orchApi.ingest(env(fx, subject, "m2"));
            OrchWorkItemDO wi = workItems.findByRuleAndSubject(fx.ruleId, subject);
            assertNotNull(wi);
            assertNotNull(wi.getActiveRunId());
            assertNotNull(wi.getPendingEventsJson());
            assertTrue(wi.getPendingEventsJson().contains("m2") || wi.getPendingEventsJson().length() > 2);
            long runCount = runs.selectCount(Wrappers.<OrchRunDO>lambdaQuery()
                    .eq(OrchRunDO::getWorkItemId, wi.getId()));
            assertEquals(1L, runCount);
            return null;
        });
    }

    @Test
    void parallelRunsConcurrently() throws Exception {
        Fixture fx = seedRule("parallel", "par");
        String subject = "par-subj";
        principals.runAs(principal(fx.actor), () -> {
            orchApi.ingest(env(fx, subject, "p1"));
            orchApi.ingest(env(fx, subject, "p2"));
            OrchWorkItemDO wi = workItems.findByRuleAndSubject(fx.ruleId, subject);
            assertNotNull(wi);
            long runCount = runs.selectCount(Wrappers.<OrchRunDO>lambdaQuery()
                    .eq(OrchRunDO::getWorkItemId, wi.getId()));
            assertEquals(2L, runCount);
            assertNotNull(wi.getActiveRunId());
            return null;
        });
    }

    private OrchApi.EventEnvelope env(Fixture fx, String subject, String suffix) {
        return new OrchApi.EventEnvelope("feishu", fx.eventType,
                "idem-" + suffix + "-" + UUID.randomUUID(),
                subject,
                Map.of("channel", "feishu", "tioka.addressed", "true"),
                Map.of("text", suffix), 0);
    }

    private Fixture seedRule(String concurrency, String tag) throws Exception {
        TestFixtures.Actor actor = fixtures.newActor("workspace_admin");
        String agentVersionId = principals.runAs(principal(actor), () -> publishAgent(actor.workspaceId(), tag));
        String eventType = "demo.wi." + tag + "." + UUID.randomUUID().toString().substring(0, 8);

        String ruleId = objectMapper.readTree(mockMvc.perform(actor.auth(post("/api/orch/rules"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + tag + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("id").asText();

        String versionId = objectMapper.readTree(mockMvc.perform(actor.auth(post("/api/orch/rules/{id}/versions", ruleId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventType":"%s",
                                  "conditionCel":"true",
                                  "actions":[{"type":"agent","agentVersionId":"%s"}],
                                  "concurrency":"%s",
                                  "acceptMode":"auto",
                                  "serviceAccountId":"%s"
                                }
                                """.formatted(eventType, agentVersionId, concurrency, actor.userId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("id").asText();

        mockMvc.perform(actor.auth(post("/api/orch/rules/{id}/versions/{vid}/publish", ruleId, versionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"STABLE\"}"))
                .andExpect(status().isOk());
        return new Fixture(actor, ruleId, eventType);
    }

    private String publishAgent(String workspaceId, String tag) {
        String planId = publishPlan(tag);
        var agent = agents.create("orch-wi-" + tag, workspaceId);
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

    private record Fixture(TestFixtures.Actor actor, String ruleId, String eventType) {}
}
