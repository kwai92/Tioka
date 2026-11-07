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
import com.tioka.orchestration.api.engine.DecisionCode;
import com.tioka.orchestration.api.engine.OrchApi;
import com.tioka.orchestration.infrastructure.mapper.OrchDecisionMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchRunMapper;
import com.tioka.orchestration.infrastructure.persistence.OrchDecisionDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ORC-03 决定强度 / accept_mode（ADR-020 修订第 2 条）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestFixtures.class)
@Tag("it")
class OrchDecisionIT {

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
    @Autowired OrchDecisionMapper decisions;
    @Autowired OrchRunMapper runs;

    @Test
    void observeInferredWouldAccept() throws Exception {
        Fixture fx = seedRule("observe", "obs-inf");
        principals.runAs(principal(fx.actor), () -> {
            OrchApi.EventAck ack = orchApi.ingest(
                    new OrchApi.EventEnvelope("feishu", fx.eventType, "idem-" + UUID.randomUUID(),
                            "subj-1", Map.of("channel", "feishu"), Map.of("text", "hi"), 0));
            assertEquals(OrchApi.Disposition.STORED, ack.disposition());
            List<OrchDecisionDO> rows = decisions.selectList(Wrappers.<OrchDecisionDO>lambdaQuery()
                    .eq(OrchDecisionDO::getEventId, ack.eventId()));
            assertEquals(1, rows.size());
            assertEquals(DecisionCode.WOULD_ACCEPT.name(), rows.get(0).getDecision());
            assertEquals(0L, runs.selectCount(Wrappers.<OrchRunDO>lambdaQuery()
                    .eq(OrchRunDO::getEventId, ack.eventId())));
            return null;
        });
    }

    @Test
    void observeExplicitAccepts() throws Exception {
        Fixture fx = seedRule("observe", "obs-exp");
        principals.runAs(principal(fx.actor), () -> {
            OrchApi.EventAck ack = orchApi.ingest(
                    new OrchApi.EventEnvelope("feishu", fx.eventType, "idem-" + UUID.randomUUID(),
                            "subj-2",
                            Map.of("channel", "feishu", "tioka.addressed", "true"),
                            Map.of("text", "hi"), 0));
            List<OrchDecisionDO> rows = decisions.selectList(Wrappers.<OrchDecisionDO>lambdaQuery()
                    .eq(OrchDecisionDO::getEventId, ack.eventId()));
            assertEquals(DecisionCode.ACCEPT.name(), rows.get(0).getDecision());
            assertTrue(runs.selectCount(Wrappers.<OrchRunDO>lambdaQuery()
                    .eq(OrchRunDO::getEventId, ack.eventId())) >= 1);
            return null;
        });
    }

    @Test
    void botEventIsInferred() throws Exception {
        Fixture fx = seedRule("auto", "bot-inf");
        principals.runAs(principal(fx.actor), () -> {
            OrchApi.EventAck ack = orchApi.ingest(
                    new OrchApi.EventEnvelope("feishu", fx.eventType, "idem-" + UUID.randomUUID(),
                            "subj-3",
                            Map.of("channel", "feishu",
                                    "tioka.addressed", "true",
                                    "tioka.actor_is_bot", "true"),
                            Map.of("text", "bot"), 0));
            List<OrchDecisionDO> rows = decisions.selectList(Wrappers.<OrchDecisionDO>lambdaQuery()
                    .eq(OrchDecisionDO::getEventId, ack.eventId()));
            assertEquals("INFERRED", rows.get(0).getStrength());
            assertEquals(DecisionCode.ACCEPT.name(), rows.get(0).getDecision());
            return null;
        });
    }

    private Fixture seedRule(String acceptMode, String tag) throws Exception {
        TestFixtures.Actor actor = fixtures.newActor("workspace_admin");
        String agentVersionId = principals.runAs(principal(actor), () -> publishAgent(actor.workspaceId(), tag));
        String eventType = "demo." + tag + "." + UUID.randomUUID().toString().substring(0, 8);

        MvcResult ruleRes = mockMvc.perform(actor.auth(post("/api/orch/rules"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + tag + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String ruleId = objectMapper.readTree(ruleRes.getResponse().getContentAsString())
                .path("data").path("id").asText();

        String versionId = objectMapper.readTree(mockMvc.perform(actor.auth(post("/api/orch/rules/{id}/versions", ruleId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventType":"%s",
                                  "conditionCel":"attrs.channel == 'feishu'",
                                  "actions":[{"type":"agent","agentVersionId":"%s"}],
                                  "concurrency":"parallel",
                                  "acceptMode":"%s",
                                  "serviceAccountId":"%s"
                                }
                                """.formatted(eventType, agentVersionId, acceptMode, actor.userId())))
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
        var agent = agents.create("orch-dec-" + tag, workspaceId);
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
