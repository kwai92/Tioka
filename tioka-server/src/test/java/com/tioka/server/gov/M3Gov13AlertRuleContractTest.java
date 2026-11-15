package com.tioka.server.gov;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GOV-13（ADR-041 §5.3；V155）：告警规则与事件迁移源码契约守护（离线、纯文件断言，
 * 做法同 {@link com.tioka.server.model.M3C9Sch09MigrationContractTest}）。
 *
 * <p>{@code ops_alert_rule} 承载规则（监控对象 + 条件 + 阈值 + 通知渠道与静默时段），
 * {@code ops_alert_event} 承载去重后的事件历史；两表均为系统级监控对象，不带
 * {@code workspace_id}，**须进** ScopeLine 旁路白名单。
 */
class M3Gov13AlertRuleContractTest {

    private static Path repoRoot;

    private static final String V155_SQL =
            "tioka-server/src/main/resources/db/migration/V155__m3_gov13_alert_rule.sql";
    private static final String V155_CONTRACT =
            "docs/contracts/db/V155__m3_gov13_alert_rule.sql";
    private static final String PERSISTENCE_PROPS =
            "tioka-framework/src/main/java/com/tioka/framework/persistence/PersistenceProperties.java";
    private static final String GOV_BIZ =
            "tioka-modules/tioka-module-governance/tioka-module-governance-biz";
    private static final String GOV_MAIN = GOV_BIZ + "/src/main/java/com/tioka/governance";
    private static final String AUTO_CONFIG_IMPORTS = GOV_BIZ
            + "/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    @BeforeAll
    static void resolveRepoRoot() {
        repoRoot = findRepoRoot();
    }

    @Test
    @DisplayName("V155：建 ops_alert_rule / ops_alert_event（枚举 CHECK）+ 唯一索引与查询索引")
    void v155CreatesAlertTables() throws IOException {
        String sql = read(V155_SQL);
        assertTrue(sql.contains("CREATE TABLE ops_alert_rule"), "V155 必须建 ops_alert_rule");
        assertTrue(sql.contains("CREATE TABLE ops_alert_event"), "V155 必须建 ops_alert_event");
        for (String col : new String[]{"target_type", "condition_type", "comparator",
                "threshold_value", "threshold_text", "window_minutes", "severity", "channels",
                "recipients", "silence_start", "silence_end", "enabled", "builtin", "last_fired_at"}) {
            assertTrue(sql.contains(col), "ops_alert_rule 缺少列：" + col);
        }
        for (String col : new String[]{"rule_id", "rule_name", "state", "dedup_key", "notified", "observed_at"}) {
            assertTrue(sql.contains(col), "ops_alert_event 缺少列：" + col);
        }
        assertTrue(sql.contains("CHECK (state IN ('FIRING','RESOLVED'))"), "事件状态必须受 CHECK 约束");
        assertTrue(sql.contains("uk_alert_rule_builtin_name"), "内置规则按名字幂等须唯一索引");
        for (String idx : new String[]{"idx_alert_rule_enabled", "idx_alert_event_rule_target",
                "idx_alert_event_state", "idx_alert_event_observed"}) {
            assertTrue(sql.contains(idx), "V155 必须建索引：" + idx);
        }
    }

    @Test
    @DisplayName("契约副本与迁移内容一致（docs/contracts/db）")
    void contractCopyMatchesMigration() throws IOException {
        assertEquals(read(V155_SQL), read(V155_CONTRACT), "V155 契约副本必须与迁移源码逐字一致");
    }

    @Test
    @DisplayName("系统级表须进 ScopeLine 旁路白名单（无 workspace_id）")
    void tablesAreScopeExempt() throws IOException {
        String props = read(PERSISTENCE_PROPS);
        assertTrue(props.contains("\"ops_alert_rule\""), "ops_alert_rule 无 workspace_id，须登记 exempt");
        assertTrue(props.contains("\"ops_alert_event\""), "ops_alert_event 无 workspace_id，须登记 exempt");
    }

    @Test
    @DisplayName("写入链路就位：DO 字段 + Mapper 投影 + 仓储门面")
    void persistenceWired() throws IOException {
        String ruleDo = read(GOV_MAIN + "/infrastructure/persistence/OpsAlertRuleDO.java");
        for (String field : new String[]{"targetType", "conditionType", "thresholdValue",
                "thresholdText", "windowMinutes", "silenceStart", "silenceEnd", "builtin", "lastFiredAt"}) {
            assertTrue(ruleDo.contains(field), "OpsAlertRuleDO 缺少字段：" + field);
        }
        String eventDo = read(GOV_MAIN + "/infrastructure/persistence/OpsAlertEventDO.java");
        for (String field : new String[]{"ruleId", "conditionType", "dedupKey", "notified", "observedAt"}) {
            assertTrue(eventDo.contains(field), "OpsAlertEventDO 缺少字段：" + field);
        }
        String ruleMapper = read(GOV_BIZ + "/src/main/resources/mapper/OpsAlertRuleMapper.xml");
        assertTrue(ruleMapper.contains("target_type     AS targetType"), "Mapper 未投影 target_type");
        assertTrue(ruleMapper.contains("last_fired_at   AS lastFiredAt"), "Mapper 未投影 last_fired_at");
        String eventMapper = read(GOV_BIZ + "/src/main/resources/mapper/OpsAlertEventMapper.xml");
        assertTrue(eventMapper.contains("dedup_key       AS dedupKey"), "Mapper 未投影 dedup_key");
        assertTrue(eventMapper.contains("IS NOT DISTINCT FROM"), "findLatest 须比较可空 target_id");
    }

    @Test
    @DisplayName("装配与内置规则就位：AutoConfiguration 登记 + 四条默认规则")
    void assemblyAndBuiltinsWired() throws IOException {
        assertTrue(read(AUTO_CONFIG_IMPORTS).contains("com.tioka.governance.config.AlertAutoConfiguration"),
                "AlertAutoConfiguration 未登记到 AutoConfiguration.imports");
        String builtins = read(GOV_MAIN + "/application/alert/BuiltinAlertRules.java");
        for (String name : new String[]{"供应商 DOWN", "任一供给侧配额达到 80%",
                "任一供给侧配额达到 100%", "10 分钟内故障接力率超过 20%"}) {
            assertTrue(builtins.contains(name), "内置规则缺失：" + name);
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
