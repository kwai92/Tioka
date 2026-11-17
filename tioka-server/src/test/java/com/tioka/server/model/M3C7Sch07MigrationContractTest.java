package com.tioka.server.model;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCH-07（ADR-066 §1.1/§1.2/§10.1；V232）：结构化输出结局与原因码列迁移源码契约守护
 * （离线、纯文件断言，做法同 {@link M3C6Sch06MigrationContractTest}）。
 *
 * <p>三列是 {@code runtime_item.content_json} 的投影：仅 {@code type='data'} 的 item 有值，
 * 供 §10.1「按（schemaRef、模型、生成方式）统计」直接聚合，无需解析 content_json。
 *
 * <p>另守护 SCH-07 登记、由 SCH-09（C-26）收口的两处结局可达性（离线；端到端由
 * {@code OutputStageIT}（@Tag("gate")）在 CI 验证）：{@code content_filter} 由抛异常改
 * {@code finishReason} 透传使 {@code CONTENT_FILTERED} 可达、人工补录 {@code HUMAN_PENDING} 经
 * {@code HumanTaskPort} 生产接线可达。
 */
class M3C7Sch07MigrationContractTest {

    private static Path repoRoot;

    private static final String V232_SQL =
            "tioka-server/src/main/resources/db/migration/V232__m3_sch07_output_outcome_columns.sql";
    private static final String V232_CONTRACT =
            "docs/contracts/db/V232__m3_sch07_output_outcome_columns.sql";
    private static final String PERSISTENCE_PROPS =
            "tioka-framework/src/main/java/com/tioka/framework/persistence/PersistenceProperties.java";
    private static final String RUNTIME_BIZ = "tioka-modules/tioka-module-runtime/tioka-module-runtime-biz"
            + "/src/main/java/com/tioka/runtime/infrastructure";
    private static final String RUNTIME_MAPPER = "tioka-modules/tioka-module-runtime/tioka-module-runtime-biz"
            + "/src/main/resources/mapper/RuntimeItemMapper.xml";
    private static final String MODEL_GATEWAY = "tioka-modules/tioka-module-model/tioka-module-model-biz"
            + "/src/main/java/com/tioka/model/application/gateway/DefaultModelGateway.java";
    private static final String OUTPUT_PIPELINE = "tioka-modules/tioka-module-ontology/tioka-module-ontology-biz"
            + "/src/main/java/com/tioka/ontology/application/schema/OutputPipeline.java";
    private static final String RUNTIME_KERNEL = "tioka-modules/tioka-module-runtime/tioka-module-runtime-biz"
            + "/src/main/java/com/tioka/runtime/kernel";
    private static final String OUTPUT_STAGE =
            RUNTIME_KERNEL + "/application/loop/OutputStage.java";
    private static final String HUMAN_TASK_SUSPEND =
            RUNTIME_KERNEL + "/approval/HumanTaskSuspendService.java";
    private static final String AGENT_LOOP =
            RUNTIME_KERNEL + "/domain/AgentLoop.java";
    private static final String KERNEL_CONFIG =
            RUNTIME_KERNEL + "/config/KernelLoopConfiguration.java";

    @BeforeAll
    static void resolveRepoRoot() {
        repoRoot = findRepoRoot();
    }

    @Test
    @DisplayName("V232：runtime_item 增 schema_ref / outcome / reason_code 三列 + 指标索引")
    void v232AddsOutcomeColumns() throws IOException {
        String sql = read(V232_SQL);
        for (String col : new String[]{"schema_ref", "outcome", "reason_code"}) {
            assertTrue(sql.contains("ADD COLUMN IF NOT EXISTS " + col),
                    "V232 必须增列（幂等）：" + col);
        }
        assertTrue(sql.contains("idx_item_output_outcome"),
                "V232 必须建 §10.1 指标聚合索引 idx_item_output_outcome");
        // 仅 data item 投影：索引条件落在 type='data'
        assertTrue(sql.contains("type = 'data'"), "指标索引必须只覆盖 data item");
    }

    @Test
    @DisplayName("契约副本与迁移内容一致（docs/contracts/db）")
    void contractCopyMatchesMigration() throws IOException {
        assertEquals(read(V232_SQL), read(V232_CONTRACT), "V232 契约副本必须与迁移源码逐字一致");
    }

    @Test
    @DisplayName("无 workspace_id 的表必须登记 ScopeLine 旁路白名单")
    void tableRegisteredAsScopeExempt() throws IOException {
        assertTrue(read(PERSISTENCE_PROPS).contains("\"runtime_item\""),
                "runtime_item 无 workspace_id，须登记 workspace-scope-exempt-tables");
    }

    @Test
    @DisplayName("写入链路就位：DO 三字段 + Mapper insert/select 三列投影")
    void outcomeWriteChainWired() throws IOException {
        String doSrc = read(RUNTIME_BIZ + "/persistence/RuntimeItemDO.java");
        for (String col : new String[]{"schemaRef", "outcome", "reasonCode"}) {
            assertTrue(doSrc.contains(col), "RuntimeItemDO 缺少结局列字段：" + col);
        }
        String mapper = read(RUNTIME_MAPPER);
        assertTrue(mapper.contains("schema_ref AS schemaRef"), "Mapper 未投影 schema_ref");
        assertTrue(mapper.contains("outcome"), "Mapper 未投影 outcome");
        assertTrue(mapper.contains("reason_code AS reasonCode"), "Mapper 未投影 reason_code");
    }

    @Test
    @DisplayName("SCH-07 遗留①：content_filter 由抛异常改 finishReason 透传，CONTENT_FILTERED 结局可达")
    void contentFilteredOutcomeReachable() throws IOException {
        String gw = read(MODEL_GATEWAY);
        assertTrue(gw.contains("content_filter 不再作为模型调用异常上抛"),
                "网关须由 SCH-09 改注释说明 content_filter 不再上抛");
        assertTrue(gw.contains("finishReason"),
                "网关须以 finishReason 透传供应商结束原因");
        // 网关不再就 content_filter 字面量分支/抛异常（映射下移到 OutputStage.rawOf）
        assertFalse(gw.contains("\"content_filter\""),
                "网关不得再对 content_filter 字面量分支（应改透传）");

        String pipeline = read(OUTPUT_PIPELINE);
        assertTrue(pipeline.contains("raw.contentFiltered()")
                        && pipeline.contains("ReasonCode.CONTENT_FILTERED"),
                "OutputPipeline.classify 须把 contentFiltered 映射为 CONTENT_FILTERED");

        String stage = read(OUTPUT_STAGE);
        assertTrue(stage.contains("\"content_filter\""),
                "OutputStage.rawOf 须把 finishReason=content_filter 映射为 filtered");
        assertTrue(stage.contains("ReasonCode.CONTENT_FILTERED"),
                "OutputStage 修复分类须处理 CONTENT_FILTERED");
    }

    @Test
    @DisplayName("SCH-07 遗留②：HUMAN_PENDING 经 HumanTaskPort 生产接线可达")
    void humanPendingOutcomeReachable() throws IOException {
        assertTrue(read(OUTPUT_STAGE).contains("HUMAN_PENDING"),
                "OutputStage 须产生 HUMAN_PENDING 结局");
        assertTrue(read(HUMAN_TASK_SUSPEND).contains("implements LoopPorts.HumanTaskPort"),
                "HumanTaskSuspendService 须实现 LoopPorts.HumanTaskPort（生产接线）");
        assertTrue(read(AGENT_LOOP).contains("resumePending"),
                "AgentLoop.run 须短接 OutputStage.resumePending 以承接人工补录");
        assertTrue(read(KERNEL_CONFIG).contains("humanTasks"),
                "KernelLoopConfiguration 须向 OutputStage 注入 HumanTaskPort");
    }

    private String read(String relative) throws IOException {
        return Files.readString(repoRoot.resolve(relative));
    }

    private static Path findRepoRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        while (cwd != null && !Files.exists(cwd.resolve("tioka-server/src/main/resources/db/migration"))) {
            cwd = cwd.getParent();
        }
        if (cwd == null) {
            throw new IllegalStateException("找不到仓库根目录");
        }
        return cwd;
    }
}
