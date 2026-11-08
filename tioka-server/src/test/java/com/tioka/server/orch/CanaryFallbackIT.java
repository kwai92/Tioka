package com.tioka.server.orch;

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
import com.tioka.orchestration.application.OrchRuleAppService;
import com.tioka.orchestration.application.OrchRuleAppService.CreateVersionCommand;
import com.tioka.orchestration.application.OrchRuleAppService.PublishCommand;
import com.tioka.orchestration.application.RuleEvalService;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(TestFixtures.class)
@Tag("it")
class CanaryFallbackIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired TestFixtures fixtures;
    @Autowired PrincipalHolder principals;
    @Autowired OrchRuleAppService rules;
    @Autowired RuleEvalService eval;
    @Autowired AgentService agents;
    @Autowired ModelAdminService models;

    @Test
    void candidateErrorFallsBackToStable() {
        TestFixtures.Actor actor = fixtures.newActor("workspace_admin");
        principals.runAs(principal(actor), () -> {
            String agentVersionId = publishAgent(actor.workspaceId(), "canary");
            var rule = rules.createRule("canary-fallback");
            var stable = rules.createVersion(rule.id(), new CreateVersionCommand(
                    "demo.canary",
                    "true",
                    List.of(Map.of("type", "agent", "agentVersionId", agentVersionId)),
                    "serial",
                    "auto",
                    "needed",
                    null, null, null,
                    actor.userId()));
            rules.publish(rule.id(), stable.id(), new PublishCommand("STABLE", null, "stable"));

            var canary = rules.createVersion(rule.id(), new CreateVersionCommand(
                    "demo.canary",
                    "int(attrs.x) > 0",
                    List.of(Map.of("type", "agent", "agentVersionId", agentVersionId)),
                    "serial",
                    "auto",
                    "needed",
                    null, null, null,
                    actor.userId()));
            rules.publish(rule.id(), canary.id(), new PublishCommand("CANARY", 100, "canary"));

            OrchRuleAppService.DryRunResult result = eval.evaluateRule(
                    rule.id(),
                    "subject-any",
                    Map.of("type", "demo.canary", "attrs", Map.of("x", "not-int")));

            assertTrue(result.fellBackToStable());
            assertEquals(stable.id(), result.versionId());
            assertTrue(result.matched());
            assertEquals("ACCEPT", result.decision());
        });
    }

    private String publishAgent(String workspaceId, String tag) {
        String planId = publishPlan(tag);
        var agent = agents.create("orch-canary-" + tag, workspaceId);
        return agents.publishSpec(agent.id(), baseSpec(agent.name(), planId, "hi")).versionId();
    }

    private String publishPlan(String tag) {
        var provider = models.createProvider(new CreateProviderRequest(tag + "-cp", "mock://x", "k"));
        String lm = models.createLogicalModel(new CreateLogicalModelRequest(tag + "-cm", "tool"));
        String binding = models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "gpt"));
        String planId = models.createPlan(new CreateRoutingPlanRequest(tag + "-crp", "FIXED", binding)).id();
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
                null, null, null, null, null, null, null, null);
    }

    private static Principal principal(TestFixtures.Actor actor) {
        return new Principal(actor.userId(), actor.workspaceId(), PrincipalType.USER,
                Set.of("workspace_admin", "system_admin"), Set.of("*"), "password");
    }
}
