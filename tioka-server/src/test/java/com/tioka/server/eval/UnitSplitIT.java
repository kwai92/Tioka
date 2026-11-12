package com.tioka.server.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.eval.api.EvalApi;
import com.tioka.eval.api.dataset.EvalCaseView;
import com.tioka.eval.api.dataset.EvalDatasetView;
import com.tioka.eval.application.DatasetService;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-18（EVO-07）验收：UnitSplit 材料单元跨层校验（ADR-068 L63 / L214）。
 * 断言：同一合同链（unit_key）被放入不同 tier 的数据集时导入被拒（CONFLICT）；
 * 同 tier 不同数据集允许（ADR-068 只禁跨层）；unit_key_path 未声明时 unit_key
 * 可空、悬空不校验；tier 与 labelStatus 枚举非法值拒绝；null tier 缺省 DEV。
 *
 * <p>装配：{@code tioka.eval.enabled=true}（DatasetService/仓储）；不开批次与
 * dispatcher（本验收无 runtime turn，无消费者条目——BatchEvalIT 僵尸防线纪律
 * 只约束开启 dispatcher 消费的上下文，见 {@link TrialsIT}）。
 */
@SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestFixtures.class)
@org.junit.jupiter.api.Tag("it")
class UnitSplitIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        registry.add("tioka.eval.enabled", () -> "true");
        registry.add("tioka.messaging.mode", () -> "in-memory");
    }

    @Autowired DatasetService datasets;
    @Autowired PrincipalHolder principals;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestFixtures fixtures;

    private TestFixtures.Actor actor;

    @Test
    void sameUnitKeyAcrossTiersIsRejectedAndSameTierAllowed() {
        actor = fixtures.newActor("member");

        // 三个数据集：DEV（A）、SEALED（B）、DEV（C）
        String dsA = create("unit-split-a-dev", "DEV");
        String dsB = create("unit-split-b-sealed", "SEALED");
        String dsC = create("unit-split-c-dev", "DEV");

        // A（DEV）：unit U1 首次导入
        EvalApi.DatasetVersionView v1 = principals.runAs(principal("*"),
                () -> datasets.importCases(dsA, List.of(evalCase("U1"))));
        assertEquals(1, v1.caseCount());

        // B（SEALED）：同 unit_key U1 跨层 → 拒绝（ADR-068 L63 一个材料单元只能属于一层）
        BizException crossTier = assertThrows(BizException.class, () ->
                principals.runAs(principal("*"), () -> datasets.importCases(dsB, List.of(evalCase("U1")))));
        assertEquals(ErrorCode.CONFLICT, crossTier.getErrorCode());
        assertTrue(crossTier.getMessage().contains("跨层"), "拒绝语义须写明跨层冲突");

        // C（DEV，另一数据集）：同 tier 同 unit_key → 允许（ADR-068 只禁跨层）
        assertDoesNotThrow(() -> principals.runAs(principal("*"),
                () -> datasets.importCases(dsC, List.of(evalCase("U1")))));

        // A 自身新版本：同 dataset 同 unit_key → 允许
        assertDoesNotThrow(() -> principals.runAs(principal("*"),
                () -> datasets.importCases(dsA, List.of(evalCase("U1")))));

        // unit_key 悬空（NULL）不校验：B（SEALED）导入无 unit_key 用例放行
        assertDoesNotThrow(() -> principals.runAs(principal("*"), () -> datasets.importCases(dsB,
                List.of(new EvalApi.EvalCase(
                        readJson("{\"text\":\"无单元键\"}"), null, "IMPORT",
                        null, null, null)))));

        // 落库字段校验：unit_key / label_status / must_pass 透出到 EvalCaseView
        List<EvalCaseView> cases = principals.runAs(principal("*"), () -> datasets.listCases(dsA, 1));
        assertEquals(1, cases.size(), "A 版本 1 的用例数");
        EvalCaseView withUnit = cases.get(0);
        assertEquals("U1", withUnit.unitKey());
        assertEquals("VALID", withUnit.labelStatus(), "labelStatus 缺省 VALID");
        assertTrue(withUnit.mustPass(), "本批 case 显式 mustPass=true");
        assertEquals("IMPORT", withUnit.source());
    }

    @Test
    void invalidTierAndLabelStatusAreRejected() {
        actor = fixtures.newActor("member");

        // tier 枚举校验（ADR-068 第 1 节五层）
        BizException badTier = assertThrows(BizException.class, () ->
                principals.runAs(principal("*"), () -> datasets.createDataset(
                        new EvalApi.CreateDatasetCmd("bad-tier", null, "PROD", null))));
        assertEquals(ErrorCode.BAD_REQUEST, badTier.getErrorCode());

        // labelStatus 枚举校验（VALID/STALE/DISPUTED）
        String ds = create("bad-label-ds", null);
        BizException badLabel = assertThrows(BizException.class, () ->
                principals.runAs(principal("*"), () -> datasets.importCases(ds,
                        List.of(new EvalApi.EvalCase(readJson("{\"text\":\"x\"}"), null,
                                "IMPORT", null, "UNKNOWN", null)))));
        assertEquals(ErrorCode.BAD_REQUEST, badLabel.getErrorCode());

        // null tier 缺省 DEV；SEALED 层创建不设额外门槛（权限点管读取，见 SealedSetIT）
        EvalDatasetView view = principals.runAs(principal("*"), () ->
                datasets.createDataset(new EvalApi.CreateDatasetCmd("default-tier", null)));
        assertEquals("DEV", view.tier(), "null tier 缺省 DEV");
    }

    // ------------------------------------------------------------------

    private String create(String name, String tier) {
        return principals.runAs(principal("*"), () -> datasets.createDataset(
                new EvalApi.CreateDatasetCmd(name, null, tier, "contract_key")).datasetId());
    }

    private EvalApi.EvalCase evalCase(String unitKey) {
        return new EvalApi.EvalCase(
                readJson("{\"text\":\"合同链 " + unitKey + "\"}"),
                readJson("{\"text\":\"期望\"}"),
                "IMPORT", unitKey, "VALID", true);
    }

    private com.fasterxml.jackson.databind.JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 全权身份（同 BatchEvalIT 直调 bean 口径；权限点隔离口径见 SealedSetIT）。 */
    private Principal principal(String... permissions) {
        Set<String> perms = permissions.length == 1 && "*".equals(permissions[0])
                ? Set.of("*") : Set.of(permissions);
        return new Principal(actor.userId(), actor.workspaceId(), PrincipalType.USER,
                Set.of("workspace_admin"), perms, "password");
    }
}
