package com.tioka.server.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.eval.api.EvalApi;
import com.tioka.eval.api.dataset.EvalCaseView;
import com.tioka.eval.application.DatasetService;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-18（EVO-07）验收：SealedSetIsolation 密封集隔离（ADR-068 L42/L47 / L213）。
 * 断言：SEALED 数据集 + 双用户（无 / 有 {@code eval:sealed:read}）——
 * 无权者 listCases 逐条内容脱敏（input/expected 置 null，元数据照常）、
 * getRun 逐条结果置空且 summaryJson 加 {@code sealedRedacted=true}
 * （汇总指标照常可见，ADR-068 L47「其他人只能看汇总指标」）；
 * 有权者全量可见；DEV tier 不受影响回归。
 *
 * <p>装配：eval 主开关 + 批次子开关（getRun 经 BatchEvalService）；运行与逐条
 * 结果经 jdbc 直插（密封隔离读路径与运行执行路径无耦合，不依赖 dispatcher，
 * 无消费者条目——僵尸防线纪律只约束开启 dispatcher 消费的上下文，见 TrialsIT）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestFixtures.class)
@org.junit.jupiter.api.Tag("it")
class SealedSetIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        registry.add("tioka.eval.enabled", () -> "true");
        registry.add("tioka.eval.batch.enabled", () -> "true");
        registry.add("tioka.messaging.mode", () -> "in-memory");
    }

    @Autowired DatasetService datasets;
    @Autowired EvalApi eval;
    @Autowired PrincipalHolder principals;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestFixtures fixtures;

    private TestFixtures.Actor actor;

    @Test
    void sealedContentHiddenWithoutPermissionAndVisibleWith() throws Exception {
        actor = fixtures.newActor("member");

        // SEALED 数据集 + 1 条用例
        String sealedDs = principals.runAs(principal(true), () -> datasets.createDataset(
                new EvalApi.CreateDatasetCmd("sealed-ds", null, "SEALED", "contract_key")).datasetId());
        principals.runAs(principal(true), () -> datasets.importCases(sealedDs, List.of(
                new EvalApi.EvalCase(readJson("{\"text\":\"密封题干\"}"),
                        readJson("{\"text\":\"密封期望\"}"), "IMPORT",
                        "unit-sealed-1", "VALID", false))));
        // DEV 数据集 + 1 条用例（回归对照）
        String devDs = principals.runAs(principal(true), () -> datasets.createDataset(
                new EvalApi.CreateDatasetCmd("dev-ds", null, "DEV", null)).datasetId());
        principals.runAs(principal(true), () -> datasets.importCases(devDs, List.of(
                new EvalApi.EvalCase(readJson("{\"text\":\"开发题干\"}"),
                        readJson("{\"text\":\"开发期望\"}"), "IMPORT", null, null, null))));

        // SEALED run + 逐条结果（jdbc 直插，见类注释）
        String runId = "run" + System.nanoTime();
        jdbc.update("""
                        INSERT INTO eval_run (id, workspace_id, kind, dataset_id, dataset_version,
                            subject_type, subject_ref, status, trials, summary_json, run_as_json)
                        VALUES (?, ?, 'BATCH', ?, 1, 'AGENT_VERSION', 'agent-x', 'COMPLETED', 1, ?, '{}')
                        """,
                runId, actor.workspaceId(), sealedDs,
                "{\"totalCases\":1,\"results\":2,\"passedCases\":1,\"failedCases\":0}");
        jdbc.update("""
                        INSERT INTO eval_result (id, run_id, case_id, evaluator_id, trial, score, passed, reason)
                        VALUES (?, ?, ?, 'evl_builtin_exact', 1, 1.0, TRUE, 'ok')
                        """,
                "res" + System.nanoTime(),
                runId,
                jdbc.queryForObject(
                        "SELECT id FROM eval_case WHERE dataset_id = ? AND version = 1",
                        String.class, sealedDs));

        // ---- 无权者：逐条内容脱敏，汇总照常（ADR-068 L47） ----
        Principal noPerm = principal(false);
        List<EvalCaseView> sealedCases = principals.runAs(noPerm, () -> datasets.listCases(sealedDs, 1));
        assertEquals(1, sealedCases.size(), "元数据（用例行）照常返回");
        assertNull(sealedCases.get(0).input(), "无权者不可见题干");
        assertNull(sealedCases.get(0).expected(), "无权者不可见期望");
        assertEquals("unit-sealed-1", sealedCases.get(0).unitKey(), "单元键为元数据，照常");

        EvalApi.EvalRunView noPermRun = principals.runAs(noPerm, () -> eval.getRun(runId));
        assertTrue(noPermRun.results().isEmpty(), "无权者逐条结果置空");
        JsonNode noPermSummary = objectMapper.readTree(noPermRun.summaryJson());
        assertTrue(noPermSummary.path("sealedRedacted").asBoolean(false),
                "summaryJson 须加 sealedRedacted=true 标记");
        assertEquals(1, noPermSummary.path("totalCases").asInt(), "汇总指标照常可见");
        assertEquals(1, noPermSummary.path("passedCases").asInt(), "汇总指标照常可见");

        // ---- 有权者：全量可见 ----
        Principal withPerm = principal(true);
        List<EvalCaseView> sealedCasesPriv = principals.runAs(withPerm, () -> datasets.listCases(sealedDs, 1));
        assertEquals("密封题干", sealedCasesPriv.get(0).input().path("text").asText());
        assertEquals("密封期望", sealedCasesPriv.get(0).expected().path("text").asText());

        EvalApi.EvalRunView privRun = principals.runAs(withPerm, () -> eval.getRun(runId));
        assertEquals(1, privRun.results().size(), "有权者逐条结果全量");
        assertEquals(1, privRun.results().get(0).trial(), "trial 列透出");
        assertFalse(objectMapper.readTree(privRun.summaryJson()).has("sealedRedacted"),
                "有权者 summary 不加脱敏标记");

        // ---- DEV tier 不受影响：无 eval:sealed:read 也全量可见 ----
        List<EvalCaseView> devCases = principals.runAs(principal(false), () -> datasets.listCases(devDs, 1));
        assertEquals("开发题干", devCases.get(0).input().path("text").asText(),
                "DEV 层不受密封隔离影响");
        // 不存在的数据集 404 口径不变
        assertEquals(ErrorCode.NOT_FOUND, assertNotFound(() ->
                principals.runAs(principal(false), () -> datasets.listCases("ds_missing", 1))));
    }

    // ------------------------------------------------------------------

    private ErrorCode assertNotFound(java.util.function.Supplier<?> body) {
        try {
            body.get();
        } catch (com.tioka.framework.error.BizException e) {
            return e.getErrorCode();
        }
        throw new AssertionError("expected NOT_FOUND");
    }

    private Principal principal(boolean sealedRead) {
        return new Principal(actor.userId(), actor.workspaceId(), PrincipalType.USER,
                Set.of("workspace_admin"),
                sealedRead ? Set.of(Permissions.EVAL_SEALED_READ, Permissions.CHAT_USE)
                        : Set.of(Permissions.CHAT_USE),
                "password");
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
