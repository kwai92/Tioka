package com.tioka.server.agent;

import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.application.AgentService;
import com.tioka.agent.infrastructure.AgentRepository;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateRoutingPlanRequest;
import com.tioka.model.application.ModelAdminService;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Tag("it")
class AgentPublishIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired AgentService agents;
    @Autowired AgentSnapshotApi snapshots;
    @Autowired AgentRepository agentRepo;
    @Autowired ModelAdminService models;
    @Autowired RuntimeApi runtime;
    @Autowired PrincipalHolder principals;
    @Autowired JdbcTemplate jdbc;

    @Test
    void snapshotFrozen() {
        String ws = Ids.newId("ws");
        principals.runAs(admin(ws), () -> {
            String planId = publishPlan("frozen");
            var agent = agents.create("frozen-agent", ws);
            AgentSpec spec = baseSpec(agent.name(), planId, "v1",
                    List.of("echo"), Map.of("echo", "approve"));
            AgentService.PublishResult p1 = agents.publishSpec(agent.id(), spec);
            String hash1 = snapshots.get(p1.versionId()).contentHash();
            AgentSpec frozen = snapshots.get(p1.versionId()).spec();

            AgentService.DraftView draft = agents.getDraft(agent.id());
            agents.saveDraft(agent.id(), baseSpec(agent.name(), planId, "v2-draft",
                    List.of("echo"), Map.of("echo", "auto")), draft.revision());
            // 已发布版本不受草稿改动影响
            assertEquals(hash1, snapshots.get(p1.versionId()).contentHash());
            assertEquals("v1", frozen.instructions().sections().get(0).text());
            assertEquals("approve", snapshots.get(p1.versionId()).spec().approval().toolPolicies().get("echo"));
        });
    }

    @Test
    void threadPinsVersion() {
        String ws = Ids.newId("ws");
        principals.runAs(admin(ws), () -> {
            String planId = publishPlan("pin");
            var agent = agents.create("pin-agent", ws);
            AgentService.PublishResult v1 = agents.publishSpec(agent.id(),
                    baseSpec(agent.name(), planId, "old", List.of(), Map.of()));
            var thr = runtime.createThread(new RuntimeApi.CreateThreadCmd(
                    v1.versionId(), "chat", null, "test", null));
            assertEquals(v1.versionId(), thr.agentVersionId());

            AgentService.PublishResult v2 = agents.publishSpec(agent.id(),
                    baseSpec(agent.name(), planId, "new", List.of(), Map.of()));
            String pinned = jdbc.queryForObject(
                    "SELECT agent_version_id FROM runtime_thread WHERE id=?",
                    String.class, thr.threadId());
            assertEquals(v1.versionId(), pinned);
            assertEquals(v2.versionId(), snapshots.currentPublished(agent.id()).versionId());

            var thr2 = runtime.createThread(new RuntimeApi.CreateThreadCmd(
                    snapshots.currentPublished(agent.id()).versionId(), "chat", null, "test", null));
            assertEquals(v2.versionId(), thr2.agentVersionId());
        });
    }

    @Test
    void rollbackMovesPointer() {
        String ws = Ids.newId("ws");
        principals.runAs(admin(ws), () -> {
            String planId = publishPlan("rb");
            var agent = agents.create("rb-agent", ws);
            AgentService.PublishResult v1 = agents.publishSpec(agent.id(),
                    baseSpec(agent.name(), planId, "one", List.of(), Map.of()));
            String hash1 = agentRepo.findVersion(v1.versionId()).getSnapshotHash();
            AgentService.PublishResult v2 = agents.publishSpec(agent.id(),
                    baseSpec(agent.name(), planId, "two", List.of(), Map.of()));
            assertEquals(v2.versionId(), snapshots.currentPublished(agent.id()).versionId());

            agents.rollback(agent.id(), v1.versionId());
            assertEquals(v1.versionId(), snapshots.currentPublished(agent.id()).versionId());
            assertEquals(hash1, agentRepo.findVersion(v1.versionId()).getSnapshotHash());
            assertEquals("one", snapshots.get(v1.versionId()).spec().instructions().sections().get(0).text());
        });
    }

    private String publishPlan(String tag) {
        var provider = models.createProvider(new CreateProviderRequest(tag + "-p", "mock://x", "k"));
        String lm = models.createLogicalModel(new CreateLogicalModelRequest(tag + "-m", "tool"));
        String binding = models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "gpt"));
        String planId = models.createPlan(new CreateRoutingPlanRequest(tag + "-rp", "FIXED", binding)).id();
        models.publish(planId);
        return planId;
    }

    private static AgentSpec baseSpec(String name, String planId, String text,
                                      List<String> builtin, Map<String, String> policies) {
        return new AgentSpec(
                1,
                new AgentSpec.Profile(name, null, null, List.of(), "CHAT"),
                new AgentSpec.Instructions(List.of(new AgentSpec.Instructions.Section("main", text))),
                new AgentSpec.ModelPolicy(planId, false),
                new AgentSpec.ToolSet(builtin, List.of(), List.of(), List.of()),
                List.of(),
                AgentSpec.KnowledgeBinding.empty(),
                AgentSpec.SandboxPolicy.defaults(),
                new AgentSpec.ApprovalSettings(policies, null),
                AgentSpec.Limits.defaults(),
                null, null, null, null);
    }

    private static Principal admin(String ws) {
        return new Principal("1", ws, PrincipalType.USER,
                Set.of("system_admin", "builder"), Set.of("*"), "test");
    }
}
