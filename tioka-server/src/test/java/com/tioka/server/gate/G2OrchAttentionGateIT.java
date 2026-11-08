package com.tioka.server.gate;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.application.AgentService;
import com.tioka.framework.id.Ids;
import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.infra.messaging.IdempotentDeliveryService;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateRoutingPlanRequest;
import com.tioka.model.application.ModelAdminService;
import com.tioka.orchestration.api.engine.OrchApi;
import com.tioka.orchestration.api.engine.OrchTools;
import com.tioka.orchestration.api.watcher.OrchWatcherConsumers;
import com.tioka.orchestration.api.watcher.TurnFinishedMessage;
import com.tioka.orchestration.infrastructure.mapper.OrchMandateMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchRuleVersionMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchRunMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchWorkItemMapper;
import com.tioka.orchestration.infrastructure.persistence.OrchMandateDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleVersionDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import com.tioka.orchestration.infrastructure.persistence.OrchWorkItemDO;
import com.tioka.orchestration.receipt.ReceiptFactKinds;
import com.tioka.orchestration.infrastructure.mapper.OrchFactMapper;
import com.tioka.orchestration.infrastructure.persistence.OrchFactDO;
import com.tioka.runtime.api.approval.CardApprovalApi;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.api.mq.RuntimeMqTopics;
import com.tioka.runtime.infrastructure.RuntimeThreadRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeThreadDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.ItemWrite;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闸门 G2：ORC-04。回执 ask → 审批台待办 → 批准 → 同 thread 新 Turn → Run RUNNING。
 */
@Tag("gate")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestFixtures.class)
class G2OrchAttentionGateIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        registry.add("tioka.messaging.mode", () -> "in-memory");
        registry.add("tioka.messaging.relay-enabled", () -> "false");
        registry.add("tioka.runtime.dispatcher-enabled", () -> "true");
    }

    @Autowired TestFixtures fixtures;
    @Autowired ObjectMapper objectMapper;
    @Autowired AgentService agents;
    @Autowired ModelAdminService models;
    @Autowired PrincipalHolder principals;
    @Autowired RuntimeApi runtime;
    @Autowired CardApprovalApi cards;
    @Autowired IdempotentDeliveryService delivery;
    @Autowired OrchRunMapper runs;
    @Autowired OrchFactMapper facts;
    @Autowired OrchWorkItemMapper workItems;
    @Autowired OrchMandateMapper mandates;
    @Autowired OrchRuleVersionMapper versions;
    @Autowired RuntimeThreadRepository threads;
    @Autowired TurnRepository turns;
    @Autowired ItemRepository items;

    @Test
    void askThenApproveResumes() throws Exception {
        TestFixtures.Actor actor = fixtures.newActor("workspace_admin");
        String token = UUID.randomUUID().toString().substring(0, 8);
        principals.runAs(principal(actor), () -> {
            String agentVersionId = publishAgent(actor.workspaceId(), "attn-" + token);

            String ruleVersionId = Ids.newId("rv");
            OrchRuleVersionDO ver = new OrchRuleVersionDO();
            ver.setId(ruleVersionId);
            ver.setRuleId(Ids.newId("rule"));
            ver.setVersion(1);
            ver.setEventType("demo.attn." + token);
            ver.setActionsJson("[{\"type\":\"agent\",\"agentVersionId\":\"" + agentVersionId + "\"}]");
            ver.setConcurrency("serial");
            ver.setAcceptMode("auto");
            ver.setTell("needed");
            ver.setEmitsJson("[]");
            ver.setServiceAccountId(actor.userId());
            ver.setStage("STABLE");
            versions.insert(ver);

            String workItemId = Ids.newId("wi");
            String threadId = Ids.newId("thr");
            String runId = Ids.newId("run");
            String turnId = Ids.newId("turn");

            RuntimeThreadDO thr = new RuntimeThreadDO();
            thr.setId(threadId);
            thr.setStatus("OPEN");
            thr.setAgentVersionId(agentVersionId);
            thr.setWorkspaceId(actor.workspaceId());
            thr.setOwnerId(actor.userId());
            thr.setLastItemSeq(0L);
            thr.setOrigin(TurnFinishedMessage.ORCH_ORIGIN);
            thr.setOriginRef(runId);
            thr.setDepth(0);
            threads.upsert(thr);

            RuntimeTurnDO turn = new RuntimeTurnDO();
            turn.setId(turnId);
            turn.setThreadId(threadId);
            turn.setSeq(1);
            turn.setStatus(TurnStatus.COMPLETED.name());
            turn.setOrigin(TurnFinishedMessage.ORCH_ORIGIN);
            turn.setOriginRef(runId);
            turn.setClientMsgId(runId + ":1");
            turn.setRunAsJson("{\"userId\":\"" + actor.userId()
                    + "\",\"workspaceId\":\"" + actor.workspaceId() + "\",\"type\":\"USER\"}");
            turn.setStepCount(1);
            turn.setLeaseVersion(0L);
            turns.insert(turn);

            OrchWorkItemDO wi = new OrchWorkItemDO();
            wi.setId(workItemId);
            wi.setWorkspaceId(actor.workspaceId());
            wi.setRuleId(ver.getRuleId());
            wi.setSubjectKey("subj-" + token);
            wi.setStatus("OPEN");
            wi.setActiveRunId(runId);
            wi.setThreadId(threadId);
            wi.setCreateAt(LocalDateTime.now());
            wi.setUpdateAt(LocalDateTime.now());
            workItems.insert(wi);

            OrchMandateDO mandate = new OrchMandateDO();
            mandate.setId(Ids.newId("mdt"));
            mandate.setWorkItemId(workItemId);
            mandate.setRuleVersionId(ruleVersionId);
            mandate.setServiceAccountId(actor.userId());
            mandate.setStrength("EXPLICIT");
            mandate.setVia("ADDRESSED");
            mandate.setStatus("ACTIVE");
            mandate.setScopeJson("{}");
            mandate.setCreateAt(LocalDateTime.now());
            mandates.insert(mandate);

            OrchRunDO run = new OrchRunDO();
            run.setId(runId);
            run.setWorkspaceId(actor.workspaceId());
            run.setWorkItemId(workItemId);
            run.setEventId(Ids.newId("evt"));
            run.setRuleVersionId(ruleVersionId);
            run.setStatus(OrchApi.RunStatus.RUNNING.name());
            run.setAttempt(1);
            run.setRewriteCount(0);
            run.setThreadId(threadId);
            run.setCurrentTurnId(turnId);
            run.setRunAsJson(turn.getRunAsJson());
            run.setCreateAt(LocalDateTime.now());
            run.setUpdateAt(LocalDateTime.now());
            runs.insert(run);

            appendReceipt(threadId, turnId, Map.of(
                    "outcome", "DONE",
                    "summary", "需要确认",
                    "actions", List.of(Map.of(
                            "kind", "ask",
                            "key", "confirm-" + token,
                            "title", "请确认",
                            "form", List.of(Map.of("name", "ok", "type", "boolean")),
                            "assignee", Map.of("users", List.of(actor.userId()))))));

            deliverFinished(actor, runId, turnId, threadId, TurnStatus.COMPLETED.name());

            OrchRunDO waiting = runs.selectById(runId);
            assertEquals(OrchApi.RunStatus.WAITING_ATTENTION.name(), waiting.getStatus());
            assertTrue(facts.selectList(Wrappers.<OrchFactDO>lambdaQuery().eq(OrchFactDO::getRunId, runId))
                    .stream().anyMatch(f -> ReceiptFactKinds.ATTENTION_OPENED.equals(f.getKind())));

            var pending = cards.findPending("orch_run", runId, "confirm-" + token);
            assertTrue(pending.isPresent(), "应有 PENDING 卡片审批");
            String approvalId = pending.get().id();

            var again = cards.openOrReuse(new CardApprovalApi.OpenCardCommand(
                    actor.workspaceId(), "orch_run", runId, "confirm-" + token,
                    "请确认", null, List.of(), Map.of("userIds", List.of(actor.userId())),
                    null, null, actor.userId()));
            assertEquals(approvalId, again.id());

            runtime.decideApproval(approvalId,
                    new RuntimeApi.ApprovalDecision(true, Map.of("ok", true), "同意"));

            OrchRunDO resumed = runs.selectById(runId);
            assertEquals(OrchApi.RunStatus.RUNNING.name(), resumed.getStatus());
            assertNotEquals(turnId, resumed.getCurrentTurnId());
            assertEquals(threadId, resumed.getThreadId());
            return null;
        });
    }

    private void appendReceipt(String threadId, String turnId, Map<String, Object> receipt) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "callId", Ids.newId("call"),
                    "name", OrchTools.RECEIPT,
                    "arguments", receipt));
            items.appendWithoutLease(ItemWrite.withoutLease(
                    threadId, turnId, 1, ItemType.TOOL_CALL.wire(), "assistant", json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void deliverFinished(TestFixtures.Actor actor, String runId, String turnId,
                                 String threadId, String status) {
        TurnFinishedMessage payload = new TurnFinishedMessage(
                turnId, threadId, status, TurnFinishedMessage.ORCH_ORIGIN, runId, null, actor.workspaceId());
        EventEnvelope env = new EventEnvelope(
                Ids.newId("evt"),
                RuntimeMqTopics.TURN_FINISHED,
                1,
                actor.workspaceId(),
                Instant.now().toString(),
                "g2-attn",
                null, null, null,
                "req_attn",
                "orch.run:" + runId,
                runId,
                0,
                new EventEnvelope.RunAs(actor.userId(), actor.workspaceId(), "USER"),
                objectMapper.convertValue(payload, Map.class));
        delivery.deliver(OrchWatcherConsumers.TURN_WATCHER, RuntimeMqTopics.TOPIC,
                RuntimeMqTopics.TURN_FINISHED, toJson(env), 0);
    }

    private String publishAgent(String workspaceId, String tag) {
        var provider = models.createProvider(new CreateProviderRequest(tag + "-p", "mock://x", "k"));
        String lm = models.createLogicalModel(new CreateLogicalModelRequest(tag + "-m", "tool"));
        String binding = models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "gpt"));
        String planId = models.createPlan(new CreateRoutingPlanRequest(tag + "-rp", "FIXED", binding)).id();
        models.publish(planId);
        var agent = agents.create("orch-attn-" + tag, workspaceId);
        return agents.publishSpec(agent.id(), new AgentSpec(
                1,
                new AgentSpec.Profile(agent.name(), null, null, List.of(), "CHAT"),
                new AgentSpec.Instructions(List.of(new AgentSpec.Instructions.Section("main", "ok"))),
                new AgentSpec.ModelPolicy(planId, false),
                AgentSpec.ToolSet.empty(),
                List.of(),
                AgentSpec.KnowledgeBinding.empty(),
                AgentSpec.SandboxPolicy.defaults(),
                AgentSpec.ApprovalSettings.empty(),
                AgentSpec.Limits.defaults(),
                null, null, null, null)).versionId();
    }

    private static Principal principal(TestFixtures.Actor actor) {
        return new Principal(actor.userId(), actor.workspaceId(), PrincipalType.USER,
                Set.of("workspace_admin", "system_admin"), Set.of("*"), "password");
    }

    private String toJson(EventEnvelope env) {
        try {
            return objectMapper.writeValueAsString(env);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
