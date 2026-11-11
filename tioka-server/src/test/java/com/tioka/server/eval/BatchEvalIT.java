package com.tioka.server.eval;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.tioka.model.api.gateway.ModelGateway;
import com.tioka.model.application.ModelAdminService;
import com.tioka.runtime.api.approval.ToolRisk;
import com.tioka.runtime.api.spi.ToolProvider;
import com.tioka.runtime.config.KernelProperties;
import com.tioka.runtime.kernel.application.TurnDispatcher;
import com.tioka.runtime.kernel.infrastructure.redis.TurnDispatchQueue;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestContainers;
import com.tioka.server.support.TestFixtures;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * EVO-02 批次评测全上下文验收（M3-06 §4.2 / ADR-031）：
 * 10 条用例数据集 × 4 内置评估器跑完出报告（eval_run COMPLETED + summary_json +
 * eval_result = 用例数 × 评估器数）；写工具用例被评测审批策略拦截（只预演，未执行）；
 * llm_judge 经 mock 模型走 purpose=EVALUATION 记账；每条用例隔离 thread（origin=eval）。
 *
 * <p>模型走 mock://openai（ProviderClient mock：消息含 "echo" 且有工具时发工具调用，
 * 否则回「【mock】已收到：{msg}」）；turn 经真实 dispatcher/worker 执行（本类开启
 * {@code tioka.runtime.dispatcher-enabled=true}，仅本上下文消费）。
 *
 * <p>C-12 治理：@SpringBootTest 上下文会被 TestContext 框架缓存到 JVM 退出，
 * 若不清理，本上下文的 TurnDispatcher 将成为游离消费者抢全局 rt:turns
 * （CI runOrder=random 下排在本类之后的 GateCluster/DualInstance 用例会被
 * 哨兵 fail-fast）。@AfterAll 停止消费并注销消费者条目（见
 * {@link #stopDispatcherConsuming()}）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestFixtures.class, BatchEvalIT.WriteToolStub.class})
@Tag("it")
class BatchEvalIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        // 本上下文负责消费 rt:turns（register 缺省 false，后注册覆盖）
        registry.add("tioka.runtime.dispatcher-enabled", () -> "true");
        registry.add("tioka.eval.enabled", () -> "true");
        registry.add("tioka.eval.batch.enabled", () -> "true");
        registry.add("tioka.messaging.mode", () -> "in-memory");
    }

    @TestConfiguration
    static class WriteToolStub {
        /** 写工具桩：被评测运行调用即计数（断言「写未真发生」= 计数为 0）。 */
        static final AtomicInteger WRITES = new AtomicInteger();

        @Bean
        ToolProvider fakeWriteTool() {
            return new ToolProvider() {
                @Override
                public String name() {
                    return "fake_write";
                }

                @Override
                public List<ToolDescriptor> listTools(String agentVersionId) {
                    return List.of(ToolDescriptor.builder()
                            .name("fake_write").description("eval write stub")
                            .inputSchema(Map.of("type", "object"))
                            .risk(ToolRisk.WRITE)
                            .build());
                }

                @Override
                public ToolResult invoke(String toolName, Map<String, Object> arguments) {
                    WRITES.incrementAndGet();
                    return new ToolResult(true, "written");
                }
            };
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestFixtures fixtures;
    @Autowired PrincipalHolder principals;
    @Autowired AgentService agents;
    @Autowired ModelAdminService models;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.tioka.eval.api.EvalApi eval;

    /** 供 @AfterAll 在缓存上下文前无害化 dispatcher（Spring 不注入静态字段，经实例方法捕获）。 */
    private static ApplicationContext cachedContext;

    @Autowired
    void captureContext(ApplicationContext context) {
        cachedContext = context;
    }

    /**
     * C-12 治理：@SpringBootTest 上下文被缓存至 JVM 退出，若不清理，本上下文的
     * TurnDispatcher 将成为游离消费者（rt:turns/rt-workers）触发 GateCluster 哨兵
     * fail-fast。两步清理：
     * ① {@link TurnDispatcher#stopConsuming()} 让读线程转入 sleep，不再抢消费；
     * ② 等待读线程退出可能挂着的 XREADGROUP（BLOCK=2s）后，经独立 Lettuce 连接
     * XGROUP DELCONSUMER 注销本上下文消费者条目（GateCluster 同款模式）——
     * 哨兵按 XINFO CONSUMERS 判定，仅 stopConsuming 不删条目仍会被命中。
     */
    @AfterAll
    static void stopDispatcherConsuming() throws InterruptedException {
        if (cachedContext == null) {
            return;
        }
        cachedContext.getBean(TurnDispatcher.class).stopConsuming();
        Thread.sleep(2100);
        String consumerName = cachedContext.getBean(KernelProperties.class).getInstanceId();
        RedisURI uri = RedisURI.builder()
                .withHost(TestContainers.REDIS.getHost())
                .withPort(TestContainers.REDIS.getMappedPort(6379))
                .build();
        try (RedisClient client = RedisClient.create(uri);
             StatefulRedisConnection<String, String> conn = client.connect()) {
            conn.sync().xgroupDelconsumer(TurnDispatchQueue.STREAM_KEY,
                    io.lettuce.core.Consumer.from(TurnDispatchQueue.GROUP, consumerName));
        } catch (Exception e) {
            System.err.println("[BatchEvalIT] 注销 consumer " + consumerName + " 失败（忽略）: " + e);
        }
    }

    private TestFixtures.Actor actor;
    private String runId;

    @Test
    void batchRunCompletesWithReportAndInterceptsWriteTool() throws Exception {
        actor = fixtures.newActor("member");
        String agentVersionId = principals.runAs(principal(), this::publishAgent);

        String datasetId = postJson("/api/eval/datasets",
                "{\"name\":\"batch-it\",\"schemaJson\":\"{}\"}").path("datasetId").asText();
        assertNotNull(datasetId);

        List<String> caseIds = new ArrayList<>();
        StringBuilder cases = new StringBuilder("[");
        for (int i = 1; i <= 9; i++) {
            String input = "case " + i + " 问答";
            String actual = "【mock】已收到：" + input;
            // expected 同时携带 text（exact）与 references（citation），互不影响
            String text = i <= 5 ? actual : "不可能匹配的期望答案";
            cases.append("{\"input\":{\"text\":\"").append(input)
                    .append("\"},\"expected\":{\"text\":\"").append(text)
                    .append("\",\"references\":[\"已收到\"]},\"source\":\"IMPORT\"},");
        }
        // 第 10 条：含 echo → mock 模型发 fake_write 工具调用 → 审批拦截
        cases.append("{\"input\":{\"text\":\"case 10: please echo write\"},")
                .append("\"expected\":{\"references\":[\"mock\",\"已收到\"]},\"source\":\"IMPORT\"}");
        cases.append("]");
        JsonNode importResp = postJson("/api/eval/datasets/" + datasetId + "/cases",
                "{\"cases\":" + cases + "}");
        assertEquals(10, importResp.path("caseCount").asInt(), "导入 10 条用例");

        // bean 直调无 HTTP 上下文：以 actor 身份执行（ScopeLine 需 Principal）
        runId = principals.runAs(principal(), () -> eval.startRun(
                new com.tioka.eval.api.EvalApi.StartEvalRunCmd(
                        datasetId, null, "AGENT_VERSION", agentVersionId,
                        List.of("evl_builtin_exact", "evl_builtin_contains",
                                "evl_builtin_citation", "evl_builtin_llm_judge"))));

        JsonNode run = awaitRun(runId, 120_000);
        assertEquals("COMPLETED", run.path("status").asText(), "批次评测完成");

        JsonNode summary = objectMapper.readTree(run.path("summaryJson").asText());
        assertEquals(10, summary.path("totalCases").asInt());
        assertEquals(40, summary.path("results").asInt(), "eval_result = 用例数 × 评估器数");
        assertEquals(1, summary.path("blockedCases").asInt(), "写工具用例被预演拦截");
        assertEquals(9, summary.path("failedCases").asInt(), "llm_judge 在 mock 模型下全失败");
        assertEquals(0, summary.path("passedCases").asInt());

        // 逐评估器断言
        JsonNode byEvaluator = summary.path("byEvaluator");
        assertEquals(5, byEvaluator.path("evl_builtin_exact").path("passed").asInt());
        assertEquals(5, byEvaluator.path("evl_builtin_exact").path("failed").asInt());
        assertEquals(5, byEvaluator.path("evl_builtin_contains").path("passed").asInt(),
                "contains 按 expected.text 口径：1-5 命中，6-10 未命中");
        assertEquals(5, byEvaluator.path("evl_builtin_contains").path("failed").asInt());
        assertEquals(9, byEvaluator.path("evl_builtin_citation").path("passed").asInt());
        assertEquals(1, byEvaluator.path("evl_builtin_citation").path("failed").asInt());
        assertEquals(10, byEvaluator.path("evl_builtin_llm_judge").path("failed").asInt(),
                "mock 模型不输出裁决 JSON → 解析失败兜底 0 分");

        assertEquals(40, run.path("results").size(), "getRun 返回全部逐条结果");
        for (JsonNode r : run.path("results")) {
            assertNotNull(r.path("caseId").asText());
            assertNotNull(r.path("turnId").asText());
            assertNotNull(r.path("evaluatorId").asText());
        }

        // 隔离 thread：10 thread + 10 turn，origin=eval，originRef=runId
        assertEquals(10, jdbc.queryForObject(
                "SELECT count(*) FROM runtime_thread WHERE origin = 'eval' AND origin_ref = ?",
                Integer.class, runId));
        assertEquals(10, jdbc.queryForObject(
                "SELECT count(*) FROM runtime_turn WHERE origin = 'eval' AND origin_ref = ?",
                Integer.class, runId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM runtime_turn WHERE origin_ref = ? AND status = 'CANCELLED'",
                Integer.class, runId), "被拦截用例 interrupt 后 CANCELLED");
        assertEquals(9, jdbc.queryForObject(
                "SELECT count(*) FROM runtime_turn WHERE origin_ref = ? AND status = 'COMPLETED'",
                Integer.class, runId));

        // 写工具桩零调用（审批拦截 = 只预演）
        assertEquals(0, WriteToolStub.WRITES.get(), "评测中写工具不得真执行");

        // llm_judge 用量经 purpose=EVALUATION 记入评测预算（不占用户 CHAT 配额路径），
        // 以本轮 10 个 turn 为作用域断言；记账有轻微异步滞后，轮询等待
        awaitCount("SELECT count(*) FROM model_usage WHERE purpose = 'EVALUATION' AND turn_id IN "
                + "(SELECT id FROM runtime_turn WHERE origin_ref = '" + runId + "')",
                10, "每条用例一次 llm_judge 调用");
        awaitCount("SELECT count(*) FROM model_usage WHERE purpose = 'CHAT' AND turn_id IN "
                + "(SELECT id FROM runtime_turn WHERE origin_ref = '" + runId + "')",
                10, "每条用例一次被测 turn 的 CHAT 调用");

        // FLOW_PUBLISH 分支登记未支持
        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.tioka.framework.error.BizException.class,
                () -> principals.runAs(principal(), () -> eval.startRun(
                        new com.tioka.eval.api.EvalApi.StartEvalRunCmd(
                                datasetId, null, "FLOW_PUBLISH", "fp_1",
                                List.of("evl_builtin_exact")))));
        assertEquals(com.tioka.framework.error.ErrorCode.NOT_IMPLEMENTED, ex.getErrorCode());
    }

    // ------------------------------------------------------------------

    private Principal principal() {
        return new Principal(actor.userId(), actor.workspaceId(), PrincipalType.USER,
                Set.of("workspace_admin", "system_admin"), Set.of("*"), "password");
    }

    /** 发布 FIXED 路由到 mock://openai 的 Agent 版本（RulePublishIT 同配方）。 */
    private String publishAgent() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        var provider = models.createProvider(new CreateProviderRequest(tag + "-p", "mock://openai", "k"));
        String lm = models.createLogicalModel(new CreateLogicalModelRequest(tag + "-m", "tool"));
        String binding = models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "gpt"));
        String planId = models.createPlan(new CreateRoutingPlanRequest(tag + "-rp", "FIXED", binding)).id();
        models.publish(planId);

        var agent = agents.create("eval-batch-agent-" + tag, actor.workspaceId());
        AgentSpec spec = new AgentSpec(
                1,
                new AgentSpec.Profile("eval-batch-agent", null, null, List.of(), "CHAT"),
                new AgentSpec.Instructions(List.of(new AgentSpec.Instructions.Section("main", "你是被测 Agent"))),
                new AgentSpec.ModelPolicy(planId, false),
                AgentSpec.ToolSet.empty(),
                List.of(),
                AgentSpec.KnowledgeBinding.empty(),
                AgentSpec.SandboxPolicy.defaults(),
                AgentSpec.ApprovalSettings.empty(),
                AgentSpec.Limits.defaults(),
                null, null, null, null, null, null, null, null);
        return agents.publishSpec(agent.id(), spec).versionId();
    }

    private JsonNode postJson(String url, String body) throws Exception {
        String resp = mockMvc.perform(actor.auth(post(url))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp);
    }

    private JsonNode getRun(String runId) throws Exception {
        String resp = mockMvc.perform(actor.auth(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/eval/runs/" + runId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp);
    }

    private JsonNode awaitRun(String runId, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        JsonNode run = getRun(runId);
        while (!"COMPLETED".equals(run.path("status").asText())
                && !"FAILED".equals(run.path("status").asText())
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            run = getRun(runId);
        }
        return run;
    }

    /** 用量记账轻微异步滞后：轮询至期望行数或超时。 */
    private void awaitCount(String sql, int expected, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        Integer count = jdbc.queryForObject(sql, Integer.class);
        while (count != null && count < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            count = jdbc.queryForObject(sql, Integer.class);
        }
        assertEquals(expected, count, message);
    }
}
