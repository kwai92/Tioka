package com.tioka.server.orch;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.application.AgentService;
import com.tioka.framework.id.Ids;
import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.framework.security.TiokaAuthentication;
import com.tioka.infra.messaging.IdempotentDeliveryService;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateRoutingPlanRequest;
import com.tioka.model.application.ModelAdminService;
import com.tioka.orchestration.api.engine.OrchTools;
import com.tioka.orchestration.api.watcher.OrchWatcherConsumers;
import com.tioka.orchestration.api.watcher.TurnFinishedMessage;
import com.tioka.orchestration.infrastructure.mapper.OrchRuleVersionMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchRunMapper;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleVersionDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import com.tioka.orchestration.infrastructure.persistence.OrchFactDO;
import com.tioka.orchestration.receipt.OrchReceiptFactMapper;
import com.tioka.orchestration.receipt.ReceiptFactKinds;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.api.mq.RuntimeMqTopics;
import com.tioka.runtime.infrastructure.RuntimeThreadRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeThreadDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.ItemWrite;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ORC-03 / PK-13：Turn 回执回写（M2-02 ReceiptIT）。
 */
@SpringBootTest
@Tag("it")
class ReceiptIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        registry.add("tioka.messaging.mode", () -> "in-memory");
    }

    @Autowired IdempotentDeliveryService delivery;
    @Autowired OrchRunMapper runMapper;
    @Autowired OrchRuleVersionMapper versionMapper;
    @Autowired OrchReceiptFactMapper factMapper;
    @Autowired RuntimeThreadRepository threads;
    @Autowired TurnRepository turns;
    @Autowired ItemRepository items;
    @Autowired ObjectMapper objectMapper;
    @Autowired PrincipalHolder principals;
    @Autowired AgentService agents;
    @Autowired ModelAdminService models;

    private String workspaceId;
    private String workItemId;
    private String ruleVersionId;
    private String threadId;
    private String agentVersionId;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new TiokaAuthentication(SystemPrincipals.system()));
        workspaceId = "ws_receipt_" + Ids.newId("ws").substring(0, 8);
        workItemId = Ids.newId("wi");
        ruleVersionId = Ids.newId("rv");
        threadId = Ids.newId("thr");
        agentVersionId = seedAgentVersion();

        OrchRuleVersionDO ver = new OrchRuleVersionDO();
        ver.setId(ruleVersionId);
        ver.setRuleId(Ids.newId("rule"));
        ver.setVersion(1);
        ver.setEventType("demo.receipt");
        ver.setActionsJson("[{\"type\":\"agent\",\"agentVersionId\":\"" + agentVersionId + "\"}]");
        ver.setConcurrency("serial");
        ver.setAcceptMode("auto");
        ver.setTell("needed");
        ver.setEmitsJson("[\"tioka.custom.allowed.v1\"]");
        ver.setServiceAccountId("sa_receipt");
        ver.setStage("STABLE");
        versionMapper.insert(ver);

        RuntimeThreadDO thr = new RuntimeThreadDO();
        thr.setId(threadId);
        thr.setStatus("OPEN");
        thr.setAgentVersionId(agentVersionId);
        thr.setWorkspaceId(workspaceId);
        thr.setOwnerId("sa_receipt");
        thr.setLastItemSeq(0L);
        thr.setOrigin(TurnFinishedMessage.ORCH_ORIGIN);
        thr.setOriginRef("pending");
        thr.setDepth(0);
        threads.upsert(thr);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void emitNotWhitelistedRejected() throws Exception {
        Fixture fx = seedRun("DISPATCHED", 0);
        appendReceipt(fx.turnId(), Map.of(
                "outcome", "DONE",
                "summary", "完成",
                "actions", List.of(Map.of(
                        "kind", "emit",
                        "type", "tioka.custom.not_allowed.v1",
                        "subjectKey", "sk1",
                        "body", Map.of("x", 1)))));

        deliverFinished(fx.runId(), fx.turnId(), TurnStatus.COMPLETED.name());

        OrchRunDO run = runMapper.selectById(fx.runId());
        assertEquals("SUCCEEDED", run.getStatus());
        assertNotNull(run.getReceiptJson());
        List<OrchFactDO> facts = facts(fx.runId());
        assertTrue(facts.stream().anyMatch(f -> ReceiptFactKinds.EMIT_REJECTED.equals(f.getKind())),
                "未白名单 emit 应记 fact emit_rejected: " + facts);
    }

    @Test
    void rewriteThenSucceed() throws Exception {
        Fixture fx = seedRun("RUNNING", 0);
        // 非法：FAILED 却带 ask
        appendReceipt(fx.turnId(), Map.of(
                "outcome", "FAILED",
                "summary", "失败却提问",
                "actions", List.of(Map.of(
                        "kind", "ask",
                        "key", "k1",
                        "title", "确认?"))));

        deliverFinished(fx.runId(), fx.turnId(), TurnStatus.COMPLETED.name());

        OrchRunDO afterRewrite = runMapper.selectById(fx.runId());
        assertEquals("RUNNING", afterRewrite.getStatus());
        assertEquals(1, afterRewrite.getRewriteCount());
        assertNotNull(afterRewrite.getCurrentTurnId());
        assertTrue(!fx.turnId().equals(afterRewrite.getCurrentTurnId()), "应开新 Turn 重写回执");

        String rewriteTurnId = afterRewrite.getCurrentTurnId();
        appendReceipt(rewriteTurnId, Map.of(
                "outcome", "DONE",
                "summary", "重写后合法"));

        deliverFinished(fx.runId(), rewriteTurnId, TurnStatus.COMPLETED.name());

        OrchRunDO done = runMapper.selectById(fx.runId());
        assertEquals("SUCCEEDED", done.getStatus());
        assertNotNull(done.getReceiptJson());
        assertTrue(done.getReceiptJson().contains("重写后合法"));
    }

    @Test
    void rewriteExhausted() throws Exception {
        Fixture fx = seedRun("RUNNING", 2);
        appendReceipt(fx.turnId(), Map.of(
                "outcome", "DONE",
                "summary", "x".repeat(2001)));

        deliverFinished(fx.runId(), fx.turnId(), TurnStatus.COMPLETED.name());

        OrchRunDO run = runMapper.selectById(fx.runId());
        assertEquals("SUCCEEDED", run.getStatus(), "Turn COMPLETED 时保留业务终态");
        assertEquals(2, run.getRewriteCount());
        List<OrchFactDO> facts = facts(fx.runId());
        assertTrue(facts.stream().anyMatch(f -> ReceiptFactKinds.RECEIPT_INVALID.equals(f.getKind())),
                "应记 receipt_invalid: " + facts);
    }

    @Test
    void missingReceiptIsResolve() throws Exception {
        Fixture fx = seedRun("DISPATCHED", 0);
        // 无 orch.receipt tool_call

        deliverFinished(fx.runId(), fx.turnId(), TurnStatus.COMPLETED.name());

        OrchRunDO run = runMapper.selectById(fx.runId());
        assertEquals("SUCCEEDED", run.getStatus());
        assertNotNull(run.getReceiptJson());
        assertTrue(run.getReceiptJson().contains("DONE"));
        List<OrchFactDO> facts = facts(fx.runId());
        assertTrue(facts.stream().anyMatch(f -> ReceiptFactKinds.MISSING_RECEIPT.equals(f.getKind())),
                "应记 missing_receipt: " + facts);
    }

    /** 播种真实 agent version（rewriteThenSucceed 走 startTurn → ensureRootGrant 需要可读快照）。 */
    private String seedAgentVersion() {
        String tag = "rcpt" + UUID.randomUUID().toString().substring(0, 8);
        return principals.runAs(new Principal("sa_receipt", workspaceId, PrincipalType.SERVICE,
                Set.of("system_admin"), Set.of("*"), "api_key"), () -> {
            var provider = models.createProvider(new CreateProviderRequest(tag + "-p", "mock://x", "k"));
            String lm = models.createLogicalModel(new CreateLogicalModelRequest(tag + "-m", "tool"));
            String binding = models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "gpt"));
            String planId = models.createPlan(new CreateRoutingPlanRequest(tag + "-rp", "FIXED", binding)).id();
            models.publish(planId);
            var agent = agents.create("receipt-it-" + tag, workspaceId);
            return agents.publishSpec(agent.id(), baseSpec(agent.name(), planId)).versionId();
        });
    }

    private static AgentSpec baseSpec(String name, String planId) {
        return new AgentSpec(
                1,
                new AgentSpec.Profile(name, null, null, List.of(), "CHAT"),
                new AgentSpec.Instructions(List.of(new AgentSpec.Instructions.Section("main", "hi"))),
                new AgentSpec.ModelPolicy(planId, false),
                AgentSpec.ToolSet.empty(),
                List.of(),
                AgentSpec.KnowledgeBinding.empty(),
                AgentSpec.SandboxPolicy.defaults(),
                AgentSpec.ApprovalSettings.empty(),
                AgentSpec.Limits.defaults(),
                null, null, null, null, null, null, null, null);
    }

    private Fixture seedRun(String status, int rewriteCount) {
        String runId = Ids.newId("run");
        String turnId = Ids.newId("turn");

        RuntimeThreadDO thr = threads.find(threadId);
        thr.setOriginRef(runId);
        threads.upsert(thr);

        RuntimeTurnDO turn = new RuntimeTurnDO();
        turn.setId(turnId);
        turn.setThreadId(threadId);
        turn.setSeq(1);
        turn.setStatus(TurnStatus.COMPLETED.name());
        turn.setOrigin(TurnFinishedMessage.ORCH_ORIGIN);
        turn.setOriginRef(runId);
        turn.setClientMsgId(runId + ":1");
        turn.setRunAsJson("{\"userId\":\"sa_receipt\",\"workspaceId\":\"" + workspaceId + "\",\"type\":\"SERVICE\"}");
        turn.setStepCount(1);
        turn.setLeaseVersion(0L);
        turns.insert(turn);

        OrchRunDO run = new OrchRunDO();
        run.setId(runId);
        run.setWorkspaceId(workspaceId);
        run.setWorkItemId(workItemId);
        run.setEventId(Ids.newId("evt"));
        run.setRuleVersionId(ruleVersionId);
        run.setStatus(status);
        run.setAttempt(1);
        run.setRewriteCount(rewriteCount);
        run.setThreadId(threadId);
        run.setCurrentTurnId(turnId);
        run.setRunAsJson(turn.getRunAsJson());
        run.setCreateAt(LocalDateTime.now());
        run.setUpdateAt(LocalDateTime.now());
        runMapper.insert(run);
        return new Fixture(runId, turnId);
    }

    private void appendReceipt(String turnId, Map<String, Object> receipt) throws Exception {
        String json = objectMapper.writeValueAsString(Map.of(
                "callId", Ids.newId("call"),
                "name", OrchTools.RECEIPT,
                "arguments", receipt));
        items.appendWithoutLease(ItemWrite.withoutLease(
                threadId, turnId, 1, ItemType.TOOL_CALL.wire(), "assistant", json));
    }

    private void deliverFinished(String runId, String turnId, String status) {
        TurnFinishedMessage payload = new TurnFinishedMessage(
                turnId, threadId, status, TurnFinishedMessage.ORCH_ORIGIN, runId, null, workspaceId);
        EventEnvelope env = new EventEnvelope(
                Ids.newId("evt"),
                RuntimeMqTopics.TURN_FINISHED,
                1,
                workspaceId,
                Instant.now().toString(),
                "receipt-it",
                null, null, null,
                "req_receipt",
                "orch.run:" + runId,
                runId,
                0,
                new EventEnvelope.RunAs("sa_receipt", workspaceId, "SERVICE"),
                objectMapper.convertValue(payload, Map.class));
        principals.runAs(new Principal("sa_receipt", workspaceId, PrincipalType.SERVICE,
                Set.of("system_admin"), Set.of("*"), "api_key"), () -> {
            delivery.deliver(OrchWatcherConsumers.TURN_WATCHER, RuntimeMqTopics.TOPIC,
                    RuntimeMqTopics.TURN_FINISHED, toJson(env), 0);
            return null;
        });
    }

    private List<OrchFactDO> facts(String runId) {
        return factMapper.selectList(Wrappers.<OrchFactDO>lambdaQuery()
                .eq(OrchFactDO::getRunId, runId)
                .orderByAsc(OrchFactDO::getSeq));
    }

    private String toJson(EventEnvelope env) {
        try {
            return objectMapper.writeValueAsString(env);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Fixture(String runId, String turnId) {}
}
