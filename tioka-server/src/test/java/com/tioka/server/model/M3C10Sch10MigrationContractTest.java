package com.tioka.server.model;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCH-10（ADR-066 §10.1 / §10.2；V234 / V235）：结构化输出指标维度、告警条件与一致性测试链的
 * 迁移源码契约守护（离线、纯文件断言，做法同 {@link M3C9Sch09MigrationContractTest}）。
 */
class M3C10Sch10MigrationContractTest {

    private static Path repoRoot;

    private static final String V234_SQL =
            "tioka-server/src/main/resources/db/migration/V234__m3_sch10_output_dimensions.sql";
    private static final String V234_CONTRACT =
            "docs/contracts/db/V234__m3_sch10_output_dimensions.sql";
    private static final String V235_SQL =
            "tioka-server/src/main/resources/db/migration/V235__m3_sch10_structured_alert_conditions.sql";
    private static final String V235_CONTRACT =
            "docs/contracts/db/V235__m3_sch10_structured_alert_conditions.sql";

    private static final String RUNTIME_API = "tioka-modules/tioka-module-runtime/tioka-module-runtime-api"
            + "/src/main/java/com/tioka/runtime";
    private static final String RUNTIME_BIZ = "tioka-modules/tioka-module-runtime/tioka-module-runtime-biz"
            + "/src/main/java/com/tioka/runtime";
    private static final String GOVERNANCE_API = "tioka-modules/tioka-module-governance/tioka-module-governance-api"
            + "/src/main/java/com/tioka/governance";
    private static final String GOVERNANCE_BIZ = "tioka-modules/tioka-module-governance/tioka-module-governance-biz"
            + "/src/main/java/com/tioka/governance";
    private static final String MODEL_BIZ = "tioka-modules/tioka-module-model/tioka-module-model-biz"
            + "/src/main/java/com/tioka/model";

    private static final String ALERT_CONDITION_TYPE = GOVERNANCE_API + "/api/alert/AlertConditionType.java";
    private static final String BUILTIN_RULES = GOVERNANCE_BIZ + "/application/alert/BuiltinAlertRules.java";
    private static final String ALERT_PROVIDER = RUNTIME_BIZ
            + "/application/metrics/StructuredOutputAlertProvider.java";

    @BeforeAll
    static void resolveRepoRoot() {
        repoRoot = findRepoRoot();
    }

    @Test
    @DisplayName("V234：runtime_item 增 model_ref/output_mode/repair_rounds 三列 + 指标聚合索引")
    void v234AddsOutputDimensionColumns() throws IOException {
        String sql = read(V234_SQL);
        for (String col : new String[]{"model_ref", "output_mode", "repair_rounds"}) {
            assertTrue(sql.contains("ADD COLUMN IF NOT EXISTS " + col), "V234 缺少维度列：" + col);
        }
        assertTrue(sql.contains("idx_item_output_dimensions"), "V234 必须建指标聚合索引");
        assertTrue(sql.contains("WHERE type = 'data'"), "索引须只覆盖 data item（部分索引）");
        assertTrue(sql.contains("runtime_item"), "V234 作用于 runtime_item");
    }

    @Test
    @DisplayName("V234 契约副本与迁移内容一致（docs/contracts/db）")
    void v234ContractCopyMatchesMigration() throws IOException {
        assertEquals(read(V234_SQL), read(V234_CONTRACT), "V234 契约副本必须与迁移源码逐字一致");
    }

    @Test
    @DisplayName("V235：condition_type 放宽到 VARCHAR(32) 并纳入两个结构化输出指标条件")
    void v235ExtendsAlertConditionType() throws IOException {
        String sql = read(V235_SQL);
        assertTrue(sql.contains("ALTER TABLE ops_alert_rule  ALTER COLUMN condition_type TYPE VARCHAR(32)")
                        || sql.contains("ALTER TABLE ops_alert_rule ALTER COLUMN condition_type TYPE VARCHAR(32)"),
                "V235 须放宽 ops_alert_rule.condition_type");
        assertTrue(sql.contains("ALTER TABLE ops_alert_event ALTER COLUMN condition_type TYPE VARCHAR(32)"),
                "V235 须同步放宽 ops_alert_event.condition_type");
        assertTrue(sql.contains("DROP CONSTRAINT ops_alert_rule_condition_type_check"), "须重建条件 CHECK");
        for (String value : new String[]{"STRUCTURED_FIRST_PASS_DROP", "STRUCTURED_FAILURE_RATE"}) {
            assertTrue(sql.contains(value), "V235 条件 CHECK 缺少：" + value);
        }
    }

    @Test
    @DisplayName("V235 契约副本与迁移内容一致（docs/contracts/db）")
    void v235ContractCopyMatchesMigration() throws IOException {
        assertEquals(read(V235_SQL), read(V235_CONTRACT), "V235 契约副本必须与迁移源码逐字一致");
    }

    @Test
    @DisplayName("告警条件枚举与缺省规则就位（5 个百分点 / 失败率 1%，窗口 7 天）")
    void alertConditionsDeclaredAndSeeded() throws IOException {
        String conditions = read(ALERT_CONDITION_TYPE);
        assertTrue(conditions.contains("STRUCTURED_FIRST_PASS_DROP"), "AlertConditionType 缺少首次通过率降幅条件");
        assertTrue(conditions.contains("STRUCTURED_FAILURE_RATE"), "AlertConditionType 缺少失败率条件");

        String rules = read(BUILTIN_RULES);
        assertTrue(rules.contains("STRUCTURED_FIRST_PASS_DROP"), "内置规则未接入首次通过率降幅条件");
        assertTrue(rules.contains("STRUCTURED_FAILURE_RATE"), "内置规则未接入失败率条件");
        assertTrue(rules.contains("5.0"), "首次通过率降幅阈值须为 5 个百分点（ADR-066 §10.1）");
        assertTrue(rules.contains("0.01"), "失败率阈值须为 1%（ADR-066 §10.1）");
        assertTrue(rules.contains("7 * 24 * 60"), "缺省窗口须为 7 天（ADR-066 §10.1）");
    }

    @Test
    @DisplayName("指标聚合链与告警信号源就位（Runtime 侧读取 V234 投影列，不解析 content_json）")
    void statsAndAlertChainWired() throws IOException {
        assertTrue(Files.exists(repoRoot.resolve(RUNTIME_API + "/api/metrics/StructuredOutputStatsApi.java")),
                "缺少 StructuredOutputStatsApi");
        assertTrue(Files.exists(repoRoot.resolve(ALERT_PROVIDER)), "缺少 AlertSignalProvider 实现");
        String mapper = read("tioka-modules/tioka-module-runtime/tioka-module-runtime-biz"
                + "/src/main/resources/mapper/RuntimeItemStatsMapper.xml");
        for (String column : new String[]{"model_ref AS modelRef", "output_mode AS outputMode",
                "schema_ref AS schemaRef", "repair_rounds"}) {
            assertTrue(mapper.contains(column), "聚合 SQL 未使用维度列：" + column);
        }
        assertTrue(mapper.contains("WHERE type = 'data'"), "聚合须只覆盖 data item");
        String provider = read(ALERT_PROVIDER);
        assertTrue(provider.contains("implements AlertSignalProvider"), "信号源须实现 AlertSignalProvider");
        assertTrue(provider.contains("STRUCTURED_FIRST_PASS_DROP") && provider.contains("STRUCTURED_FAILURE_RATE"),
                "信号源须支持两个结构化输出指标条件");
    }

    @Test
    @DisplayName("一致性测试链就位（测试集 / 执行 SPI / 接入测试写回）")
    void conformanceChainWired() {
        String base = MODEL_BIZ + "/application/conformance/";
        for (String file : new String[]{"ConformanceProbe.java", "ConformanceProbeSet.java",
                "ConformanceProbeExecutor.java", "ModelConformanceRunner.java"}) {
            assertTrue(Files.exists(repoRoot.resolve(base + file)), "缺少一致性测试组件：" + file);
        }
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
