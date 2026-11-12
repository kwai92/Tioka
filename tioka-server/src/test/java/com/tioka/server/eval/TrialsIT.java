package com.tioka.server.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.application.AgentService;
import com.tioka.eval.api.EvalApi;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateRoutingPlanRequest;
import com.tioka.model.application.ModelAdminService;
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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-18（EVO-07）验收：TrialsIT 多次运行（ADR-068 第 5 节 / L219）。
 * 断言：trials=2 小数据集（2 用例 × 2 评估器）——
 * <ul>
 *   <li>eval_result 行数 = 用例 × 评估器 × trial 且 trial 列取值 1/2 正确；</li>
 *   <li>每 trial 独立隔离 thread（thread 数 = 用例 × trial，origin=eval）；</li>
 *   <li>summary.metrics 三指标数值正确（确定性行为：mock 模型回声 + exact/contains）：
 *       singleSuccessRate=0.5、passK=0.5、consistencyRate=1.0、mustPassFailed=1；</li>
 *   <li>既有 trials=1 路径（旧 5 参构造，trials 缺省 1）：result 行数 = 用例 × 评估器、
 *       trial 列全 1（BatchEvalIT 口径不变）；</li>
 *   <li>trials 越界（0/11）拒绝。</li>
 * </ul>
 *
 * <p>C-12 治理：@SpringBootTest 上下文开启 {@code tioka.runtime.dispatcher-enabled=true}
 * （真跑 turn），@AfterAll 照抄 {@link BatchEvalIT} 僵尸防线纪律——stopConsuming +
 * XGROUP DELCONSUMER 注销本上下文消费者条目，防止游离消费者抢全局 rt:turns。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestFixtures.class)
@Tag("it")
class TrialsIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        registry.add("tioka.runtime.dispatcher-enabled", () -> "true");
        registry.add("tioka.eval.enabled", () -> "true");
        registry.add("tioka.eval.batch.enabled", () -> "true");
        registry.add("tioka.messaging.mode", () -> "in-memory");
    }

    @Autowired ObjectMapper objectMapper;
    @Autowired TestFixtures fixtures;
    @Autowired PrincipalHolder principals;
    @Autowired AgentService agents;
    @Autowired ModelAdminService models;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.tioka.eval.api.EvalApi eval;

    private static ApplicationContext cachedContext;

    @Autowired
    void captureContext(ApplicationContext context) {
        cachedContext = context;
    }

    /** 僵尸防线（照抄 BatchEvalIT @AfterAll 纪律，见类注释）。 */
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
            System.err.println("[TrialsIT] 注销 consumer " + consumerName + " 失败（忽略）: " + e);
        }
    }

    private TestFixtures.Actor actor;
    private String agentVersionId;

    @Test
    void trialsExpandResultsThreadsAndMetrics() throws Exception {
        actor = fixtures.newActor("member");
        agentVersionId = principals.runAs(principal(), this::publishAgent);

        // 2 用例 × 2 评估器（exact + contains），mock 模型回声 → 确定性：
        // case1 expected.text = 回声全文（exact/contains 双过）；case2 不匹配（mustPass=true）
        String actual1 = "【mock】已收到：case 1 问答";
        String datasetId = createDatasetWithCases(List.of(
                caseJson("u-a", "{\"text\":\"case 1 问答\"}",
                        "{\"text\":\"" + actual1 + "\"}", false),
                caseJson("u-b", "{\"text\":\"case 2 问答\"}",
                        "{\"text\":\"不可能匹配的期望答案\"}", true)));

        String runId = principals.runAs(principal(), () -> eval.startRun(
                new EvalApi.StartEvalRunCmd(datasetId, null, "AGENT_VERSION", agentVersionId,
                        List.of("evl_builtin_exact", "evl_builtin_contains"), 2)));

        JsonNode run = awaitRun(runId, 120_000);
        assertEquals("COMPLETED", run.path("status").asText(), "trials=2 批次评测完成");

        JsonNode summary = objectMapper.readTree(run.path("summaryJson").asText());
        assertEquals(2, summary.path("trials").asInt());
        // result 行数 = 用例 × 评估器 × trial = 2×2×2 = 8
        assertEquals(8, summary.path("results").asInt());
        Integer resultRows = jdbc.queryForObject(
                "SELECT count(*) FROM eval_result WHERE run_id = ?", Integer.class, runId);
        assertEquals(8, resultRows, "eval_result = 用例 × 评估器 × trial");
        // trial 列取值正确：每 (case × evaluator) 恰有 trial=1 与 trial=2 各一条
        assertEquals(4, jdbc.queryForObject(
                "SELECT count(*) FROM eval_result WHERE run_id = ? AND trial = 1",
                Integer.class, runId));
        assertEquals(4, jdbc.queryForObject(
                "SELECT count(*) FROM eval_result WHERE run_id = ? AND trial = 2",
                Integer.class, runId));
        // trial 与 turn 对应：每 (case, evaluator) 的两条结果 turn_id 不同（独立 thread）
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM (
                  SELECT case_id, evaluator_id, count(DISTINCT turn_id) AS turns
                  FROM eval_result WHERE run_id = ?
                  GROUP BY case_id, evaluator_id HAVING count(DISTINCT turn_id) <> 2
                ) t
                """, Integer.class, runId), "每个 (case × evaluator) 两个 trial 各自独立 turn");

        // 每 trial 独立隔离 thread：thread 数 = 用例 × trial = 4，origin=eval
        assertEquals(4, jdbc.queryForObject(
                "SELECT count(*) FROM runtime_thread WHERE origin = 'eval' AND origin_ref = ?",
                Integer.class, runId));
        assertEquals(4, jdbc.queryForObject(
                "SELECT count(*) FROM runtime_turn WHERE origin = 'eval' AND origin_ref = ?",
                Integer.class, runId));

        // 逐 trial 通过口径：case1 全过（两评估器两 trial），case2 全败
        assertEquals(1, summary.path("passedCases").asInt());
        assertEquals(1, summary.path("failedCases").asInt());
        assertEquals(0, summary.path("blockedCases").asInt());
        // must_pass 用例（case2）任一判定失败 → 计数上报（阻断决策在 EVO-06，登记偏离）
        assertEquals(1, summary.path("mustPassFailed").asInt());

        // metrics（确定性行为下三指标）：exact 与 contains 同分布
        for (String evaluator : List.of("evl_builtin_exact", "evl_builtin_contains")) {
            JsonNode m = summary.path("metrics").path(evaluator);
            assertEquals(0.5, m.path("singleSuccessRate").asDouble(), 1e-9,
                    evaluator + " 单次成功率 = (1 + 0)/2 用例");
            assertEquals(0.5, m.path("passK").asDouble(), 1e-9,
                    evaluator + " pass^2 = 2 用例中 1 个两次全过");
            assertEquals(1.0, m.path("consistencyRate").asDouble(), 1e-9,
                    evaluator + " 回声模型两次输出一致");
            JsonNode ci = m.path("clusteredCi");
            assertEquals(2, ci.path("unitCount").asInt(), "unit u-a / u-b");
            assertEquals(4, ci.path("judgmentCount").asInt(), "2 用例 × 2 trial");
            assertEquals(0.5, ci.path("mean").asDouble(), 1e-9);
            assertFalseSufficient(ci, evaluator);
            assertEquals(0, ci.path("excludedCount").asInt(), "本 run 无 STALE/DISPUTED");
        }

        // getRun 逐条透出 trial（EvalApi.ResultView）
        EvalApi.EvalRunView view = principals.runAs(principal(), () -> eval.getRun(runId));
        assertEquals(8, view.results().size());
        assertTrue(view.results().stream().allMatch(r -> r.trial() == 1 || r.trial() == 2));
    }

    @Test
    void defaultTrialsKeepsLegacyShapeAndOutOfRangeRejected() throws Exception {
        actor = fixtures.newActor("member");
        agentVersionId = principals.runAs(principal(), this::publishAgent);

        String datasetId = createDatasetWithCases(List.of(
                caseJson(null, "{\"text\":\"case 1 问答\"}",
                        "{\"text\":\"【mock】已收到：case 1 问答\"}", false)));

        // 旧 5 参构造（trials 缺省 1）：BatchEvalIT 口径不变
        String runId = principals.runAs(principal(), () -> eval.startRun(
                new EvalApi.StartEvalRunCmd(datasetId, null, "AGENT_VERSION", agentVersionId,
                        List.of("evl_builtin_exact"))));
        JsonNode run = awaitRun(runId, 120_000);
        assertEquals("COMPLETED", run.path("status").asText());
        JsonNode summary = objectMapper.readTree(run.path("summaryJson").asText());
        assertEquals(1, summary.path("trials").asInt());
        assertEquals(1, summary.path("results").asInt(), "trials=1 行数 = 用例 × 评估器");
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM eval_result WHERE run_id = ? AND trial = 1",
                Integer.class, runId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM runtime_thread WHERE origin = 'eval' AND origin_ref = ?",
                Integer.class, runId), "trials=1 每用例一个 thread（既有口径）");
        assertEquals(1.0, summary.path("metrics").path("evl_builtin_exact")
                .path("consistencyRate").asDouble(), 1e-9, "单 trial 一致率恒为 1");

        // trials 越界拒绝（ADR-068 第 5 节：可配 1~10）
        for (int bad : List.of(0, 11)) {
            var ex = org.junit.jupiter.api.Assertions.assertThrows(
                    com.tioka.framework.error.BizException.class, () -> principals.runAs(principal(),
                            () -> eval.startRun(new EvalApi.StartEvalRunCmd(datasetId, null,
                                    "AGENT_VERSION", agentVersionId,
                                    List.of("evl_builtin_exact"), bad))));
            assertEquals(com.tioka.framework.error.ErrorCode.BAD_REQUEST, ex.getErrorCode());
        }
    }

    // ------------------------------------------------------------------

    private void assertFalseSufficient(JsonNode ci, String evaluator) {
        org.junit.jupiter.api.Assertions.assertFalse(ci.path("sampleSufficient").asBoolean(),
                evaluator + " 仅 2 个材料单元 → 样本不足（ADR-068 L151）");
    }

    private Principal principal() {
        return new Principal(actor.userId(), actor.workspaceId(), PrincipalType.USER,
                Set.of("workspace_admin", "system_admin"), Set.of("*"), "password");
    }

    private String caseJson(String unitKey, String input, String expected, boolean mustPass) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"input\":").append(input)
                .append(",\"expected\":").append(expected)
                .append(",\"source\":\"IMPORT\"");
        if (unitKey != null) {
            sb.append(",\"unitKey\":\"").append(unitKey).append("\"");
        }
        if (mustPass) {
            sb.append(",\"mustPass\":true");
        }
        sb.append("}");
        return sb.toString();
    }

    private String createDatasetWithCases(List<String> casesJson) {
        String datasetId = principals.runAs(principal(), () ->
                eval.createDataset(new EvalApi.CreateDatasetCmd("trials-it-" + UUID.randomUUID(), null, "DEV", null)).datasetId());
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < casesJson.size(); i++) {
            if (i > 0) {
                arr.append(",");
            }
            arr.append(casesJson.get(i));
        }
        arr.append("]");
        principals.runAs(principal(), () -> eval.importCases(datasetId, parseCases(arr.toString())));
        return datasetId;
    }

    private List<EvalApi.EvalCase> parseCases(String json) {
        try {
            JsonNode arr = objectMapper.readTree(json);
            List<EvalApi.EvalCase> out = new java.util.ArrayList<>();
            for (JsonNode n : arr) {
                out.add(new EvalApi.EvalCase(
                        n.path("input"), n.path("expected"),
                        n.path("source").asText("IMPORT"),
                        n.hasNonNull("unitKey") ? n.path("unitKey").asText() : null,
                        n.hasNonNull("labelStatus") ? n.path("labelStatus").asText() : null,
                        n.has("mustPass") ? n.path("mustPass").asBoolean() : null));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 发布 FIXED 路由到 mock://openai 的 Agent 版本（BatchEvalIT 同配方）。 */
    private String publishAgent() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        var provider = models.createProvider(new CreateProviderRequest(tag + "-p", "mock://openai", "k"));
        String lm = models.createLogicalModel(new CreateLogicalModelRequest(tag + "-m", "tool"));
        String binding = models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "gpt"));
        String planId = models.createPlan(new CreateRoutingPlanRequest(tag + "-rp", "FIXED", binding)).id();
        models.publish(planId);

        var agent = agents.create("eval-trials-agent-" + tag, actor.workspaceId());
        AgentSpec spec = new AgentSpec(
                1,
                new AgentSpec.Profile("eval-trials-agent", null, null, List.of(), "CHAT"),
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

    /** bean 直调 getRun（免 MockMvc；runAs 保证 ScopeLine 空间过滤取到 run 行）。 */
    private JsonNode getRun(String runId) throws Exception {
        EvalApi.EvalRunView view = principals.runAs(principal(), () -> eval.getRun(runId));
        com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
        node.put("status", view.status());
        node.put("summaryJson", view.summaryJson() == null ? "" : view.summaryJson());
        return node;
    }
}
