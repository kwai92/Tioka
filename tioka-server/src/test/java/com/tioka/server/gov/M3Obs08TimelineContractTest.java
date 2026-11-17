package com.tioka.server.gov;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3-B35（OBS-08）：统一时间线投影源码守护（离线、纯文件断言，做法同
 * {@link M3Obs07LedgerContractTest} / {@code M3C1aMigrationContractTest}）。
 *
 * <p>守护 ADR-075 §2 的「投影不是事实源」定位（异步生成、可按时间段重建、幂等、不进哈希链）
 * 与 §4 的查询与权限过滤（数据标签 / 空间 / {@code trajectory:read:content}）。
 * 端到端语义（真实 PG 写入、投影、重建、游标分页）由 CI 的 IT 与
 * {@code G3TimelineGateIT} 承接。
 */
class M3Obs08TimelineContractTest {

    private static Path repoRoot;

    private static final String GOV_BIZ_MAIN =
            "tioka-modules/tioka-module-governance/tioka-module-governance-biz/src/main";
    private static final String GOV_API_MAIN =
            "tioka-modules/tioka-module-governance/tioka-module-governance-api/src/main/java";

    private static final String MIGRATION =
            "tioka-server/src/main/resources/db/migration/V229__m3_obs08_timeline.sql";
    private static final String MIGRATION_CONTRACT = "docs/contracts/db/V229__m3_obs08_timeline.sql";
    private static final String EVENT_SCHEMA =
            "docs/contracts/events/tioka.obs.timeline_source.v1.json";

    private static final String CONTROLLER =
            GOV_BIZ_MAIN + "/java/com/tioka/governance/api/TimelineController.java";
    private static final String CONSUMER =
            GOV_BIZ_MAIN + "/java/com/tioka/governance/application/timeline/TimelineProjectionConsumer.java";
    private static final String PROJECTOR =
            GOV_BIZ_MAIN + "/java/com/tioka/governance/application/timeline/DefaultTimelineProjector.java";
    private static final String QUERY =
            GOV_BIZ_MAIN + "/java/com/tioka/governance/application/timeline/DefaultTimelineQuery.java";
    private static final String AUTO_CONFIG =
            GOV_BIZ_MAIN + "/java/com/tioka/governance/config/TimelineAutoConfiguration.java";
    private static final String MANIFEST_AUTO_CONFIG =
            GOV_BIZ_MAIN + "/java/com/tioka/governance/config/RunManifestAutoConfiguration.java";
    private static final String IMPORTS =
            GOV_BIZ_MAIN + "/resources/META-INF/spring/org.springframework.boot.autoconfigure"
                    + ".AutoConfiguration.imports";
    private static final String MAPPER_XML =
            GOV_BIZ_MAIN + "/resources/mapper/TimelineEntryMapper.xml";
    private static final String MAPPER_JAVA =
            GOV_BIZ_MAIN + "/java/com/tioka/governance/infrastructure/mapper/TimelineEntryMapper.java";
    private static final String REBUILD_SOURCE =
            GOV_BIZ_MAIN + "/java/com/tioka/governance/application/timeline/RunManifestTimelineRebuildSource.java";
    private static final String SHARED_PROJECTION =
            GOV_BIZ_MAIN + "/java/com/tioka/governance/application/timeline/RunManifestTimelineProjection.java";
    private static final String PERMISSIONS =
            "tioka-framework/src/main/java/com/tioka/framework/security/Permissions.java";
    private static final String PERSISTENCE_PROPERTIES =
            "tioka-framework/src/main/java/com/tioka/framework/persistence/PersistenceProperties.java";
    private static final String MQ_TOPICS =
            GOV_API_MAIN + "/com/tioka/governance/api/mq/GovernanceMqTopics.java";

    /* 其余来源：台账（system）/ 运行项（runtime）/ 模型用量（model）/ 规则求值（orchestration）
       / 反馈（eval）——写入与重建分处各 biz。 */
    private static final String SYSTEM_BIZ_MAIN =
            "tioka-modules/tioka-module-system/tioka-module-system-biz/src/main";
    private static final String RUNTIME_BIZ_MAIN =
            "tioka-modules/tioka-module-runtime/tioka-module-runtime-biz/src/main";
    private static final String MODEL_BIZ_MAIN =
            "tioka-modules/tioka-module-model/tioka-module-model-biz/src/main";
    private static final String ORCH_BIZ_MAIN =
            "tioka-modules/tioka-module-orchestration/tioka-module-orchestration-biz/src/main";
    private static final String EVAL_BIZ_MAIN =
            "tioka-modules/tioka-module-eval/tioka-module-eval-biz/src/main";

    private static final String EXT_PROJECTION = SYSTEM_BIZ_MAIN
            + "/java/com/tioka/system/application/obs/ExtInteractionTimelineProjection.java";
    private static final String EXT_REBUILD = SYSTEM_BIZ_MAIN
            + "/java/com/tioka/system/application/obs/ExtInteractionTimelineRebuildSource.java";
    private static final String ITEM_PROJECTION = RUNTIME_BIZ_MAIN
            + "/java/com/tioka/runtime/timeline/RuntimeItemTimelineProjection.java";
    private static final String ITEM_REBUILD = RUNTIME_BIZ_MAIN
            + "/java/com/tioka/runtime/timeline/RuntimeItemTimelineRebuildSource.java";
    private static final String USAGE_PROJECTION = MODEL_BIZ_MAIN
            + "/java/com/tioka/model/application/timeline/ModelUsageTimelineProjection.java";
    private static final String USAGE_REBUILD = MODEL_BIZ_MAIN
            + "/java/com/tioka/model/application/timeline/ModelUsageTimelineRebuildSource.java";
    private static final String ITEM_REPOSITORY = RUNTIME_BIZ_MAIN
            + "/java/com/tioka/runtime/kernel/infrastructure/ItemRepository.java";
    private static final String ITEM_SINK = RUNTIME_BIZ_MAIN
            + "/java/com/tioka/runtime/kernel/infrastructure/MemoryItemSink.java";
    private static final String USAGE_REPOSITORY = MODEL_BIZ_MAIN
            + "/java/com/tioka/model/infrastructure/ModelUsageRepository.java";
    private static final String USAGE_RECORDER = MODEL_BIZ_MAIN
            + "/java/com/tioka/model/application/gateway/UsageRecorder.java";
    private static final String APPROVAL_PROJECTION = RUNTIME_BIZ_MAIN
            + "/java/com/tioka/runtime/timeline/RuntimeApprovalTimelineProjection.java";
    private static final String APPROVAL_REBUILD = RUNTIME_BIZ_MAIN
            + "/java/com/tioka/runtime/timeline/RuntimeApprovalTimelineRebuildSource.java";
    private static final String APPROVAL_REPOSITORY = RUNTIME_BIZ_MAIN
            + "/java/com/tioka/runtime/kernel/infrastructure/ApprovalRepository.java";
    private static final String APPROVAL_MAPPER_XML = RUNTIME_BIZ_MAIN
            + "/resources/mapper/RuntimeApprovalMapper.xml";
    private static final String DECISION_PROJECTION = ORCH_BIZ_MAIN
            + "/java/com/tioka/orchestration/timeline/OrchDecisionTimelineProjection.java";
    private static final String DECISION_REBUILD = ORCH_BIZ_MAIN
            + "/java/com/tioka/orchestration/timeline/OrchDecisionTimelineRebuildSource.java";
    private static final String DECISION_MAPPER_XML = ORCH_BIZ_MAIN
            + "/resources/mapper/OrchDecisionMapper.xml";
    private static final String FEEDBACK_PROJECTION = EVAL_BIZ_MAIN
            + "/java/com/tioka/eval/timeline/EvalFeedbackTimelineProjection.java";
    private static final String FEEDBACK_REBUILD = EVAL_BIZ_MAIN
            + "/java/com/tioka/eval/timeline/EvalFeedbackTimelineRebuildSource.java";
    private static final String FEEDBACK_REPOSITORY = EVAL_BIZ_MAIN
            + "/java/com/tioka/eval/infrastructure/EvalFeedbackRepository.java";
    private static final String CHANGE_PROJECTION = RUNTIME_BIZ_MAIN
            + "/java/com/tioka/runtime/timeline/TrustActionTimelineProjection.java";
    private static final String CHANGE_REBUILD = RUNTIME_BIZ_MAIN
            + "/java/com/tioka/runtime/timeline/TrustActionTimelineRebuildSource.java";
    private static final String CHANGE_REPOSITORY = RUNTIME_BIZ_MAIN
            + "/java/com/tioka/runtime/infrastructure/TrustActionRepository.java";
    private static final String CHANGE_MAPPER_XML = RUNTIME_BIZ_MAIN
            + "/resources/mapper/TrustActionMapper.xml";
    private static final String CONFIG_PROJECTION = GOV_BIZ_MAIN
            + "/java/com/tioka/governance/application/timeline/CfgChangeTimelineProjection.java";
    private static final String CONFIG_REBUILD = GOV_BIZ_MAIN
            + "/java/com/tioka/governance/application/timeline/CfgChangeTimelineRebuildSource.java";
    private static final String CONFIG_MAPPER_JAVA = GOV_BIZ_MAIN
            + "/java/com/tioka/governance/infrastructure/mapper/CfgChangeMapper.java";
    private static final String CONFIG_MAPPER_XML = GOV_BIZ_MAIN + "/resources/mapper/CfgChangeMapper.xml";
    private static final String SECURITY_PROJECTION = SYSTEM_BIZ_MAIN
            + "/java/com/tioka/system/application/obs/AuditEventTimelineProjection.java";
    private static final String SECURITY_REBUILD = SYSTEM_BIZ_MAIN
            + "/java/com/tioka/system/application/obs/AuditEventTimelineRebuildSource.java";
    private static final String SECURITY_ACTIONS = SYSTEM_BIZ_MAIN
            + "/java/com/tioka/system/application/obs/SecurityAuditActions.java";
    private static final String SECURITY_MAPPER_JAVA = SYSTEM_BIZ_MAIN
            + "/java/com/tioka/system/infrastructure/mapper/AuditEventMapper.java";
    private static final String SECURITY_MAPPER_XML = SYSTEM_BIZ_MAIN + "/resources/mapper/AuditEventMapper.xml";
    private static final String SECURITY_REPOSITORY = SYSTEM_BIZ_MAIN
            + "/java/com/tioka/system/infrastructure/AuditEventRepository.java";
    private static final String AUDIT_ACTIONS_YAML = "docs/contracts/audit/actions.yaml";

    @BeforeAll
    static void resolveRepoRoot() {
        repoRoot = findRepoRoot();
    }

    @Test
    @DisplayName("迁移：V229 双份副本 byte-identical，含三表 + 来源事件唯一索引 + 幂等键列")
    void migrationCreatesProjectionTables() throws IOException {
        byte[] server = Files.readAllBytes(repoRoot.resolve(MIGRATION));
        byte[] contract = Files.readAllBytes(repoRoot.resolve(MIGRATION_CONTRACT));
        assertArrayEquals(server, contract,
                "迁移须双份同步（byte-identical）：" + MIGRATION + " 与 " + MIGRATION_CONTRACT);

        String sql = new String(server, StandardCharsets.UTF_8);
        for (String table : new String[]{
                "CREATE TABLE IF NOT EXISTS timeline_entry",
                "CREATE TABLE IF NOT EXISTS timeline_entry_subject",
                "CREATE TABLE IF NOT EXISTS timeline_projection_cursor"}) {
            assertTrue(sql.contains(table), "V229 缺少投影表：" + table);
        }
        assertTrue(sql.contains("CREATE UNIQUE INDEX IF NOT EXISTS uq_timeline_entry_source_event"),
                "投影幂等键（source_event_id）须为唯一索引，供 ON CONFLICT 推断");
        assertTrue(sql.contains("source_event_id   VARCHAR(64),"),
                "timeline_entry 须带来源事件幂等键列 source_event_id");
        assertTrue(sql.contains("CHECK (category IN"), "category 须受 12 类枚举约束");
        assertFalse(sql.toLowerCase().contains("tenantid"), "全程禁止引入 tenantId（ADR-029）");
    }

    @Test
    @DisplayName("权限点：trajectory:read:content 存在并纳入 ALL_CONCRETE")
    void trajectoryReadContentPermissionDeclared() throws IOException {
        String src = read(PERMISSIONS);
        assertTrue(src.contains("TRAJECTORY_READ_CONTENT = \"trajectory:read:content\""),
                "缺少权限点 trajectory:read:content（ADR-075 §4）");
        int idx = src.indexOf("ALL_CONCRETE");
        assertTrue(idx > 0, "缺少 ALL_CONCRETE 数组");
        assertTrue(src.substring(idx).contains("TRAJECTORY_READ_CONTENT"),
                "新权限点必须登记进 ALL_CONCRETE");
    }

    @Test
    @DisplayName("查询出口：请求 / 主体双视角 + 平台级重建，audit:read 授权，标签上限由权限决定")
    void controllerExposesQueryAndRebuild() throws IOException {
        String src = read(CONTROLLER);
        assertTrue(src.contains("/api/governance/timeline"), "端点前缀固定");
        assertTrue(src.contains("\"/request\"") && src.contains("\"/subject\""),
                "须提供请求时间线与主体时间线两个视角（ADR-075 §4）");
        assertTrue(src.contains("\"/rebuild\"") && src.contains("PostMapping"),
                "须提供按时间段重建出口（ADR-075 §2）");
        assertTrue(src.contains("Permissions.AUDIT_READ"), "查询入口须以 audit:read 授权");
        assertTrue(src.contains("requirePlatformAdmin()") && src.contains("ErrorCode.FORBIDDEN"),
                "重建为平台级运维动作，非管理员须被拒");
        assertTrue(src.contains("Permissions.TRAJECTORY_READ_CONTENT") && src.contains("DataLabel.SECRET")
                        && src.contains("DataLabel.INTERNAL"),
                "标签上限：无 trajectory:read:content 时收敛到 INTERNAL，持有时到 SECRET");
    }

    @Test
    @DisplayName("装配：消费者绑定 timeline_source tag，自动配置登记 imports，来源缺失即不参与重建")
    void wiringBindsConsumerAndRebuildSources() throws IOException {
        String consumer = read(CONSUMER);
        assertTrue(consumer.contains("extends IdempotentConsumer"),
                "投影消费者须复用幂等消费者样板");
        assertTrue(consumer.contains("GovernanceMqTopics.TOPIC"),
                "消费者须订阅 tioka-governance topic");
        assertTrue(consumer.contains("GovernanceMqTopics.TIMELINE_SOURCE"),
                "消费者须绑定 tioka.obs.timeline_source.v1 tag");

        String auto = read(AUTO_CONFIG);
        assertTrue(auto.contains("@ConditionalOnProperty(prefix = \"tioka.obs.timeline\""),
                "投影装配须可由 tioka.obs.timeline.enabled 关闭");
        assertTrue(auto.contains("ObjectProvider<TimelineRebuildSource>"),
                "重建来源须以 ObjectProvider 可选收集（缺失即不参与重建）");

        String imports = read(IMPORTS);
        assertTrue(imports.contains("com.tioka.governance.config.TimelineAutoConfiguration"),
                "自动配置类须登记进 AutoConfiguration.imports");

        String manifest = read(MANIFEST_AUTO_CONFIG);
        assertTrue(manifest.contains("TimelineRebuildSource")
                        && manifest.contains("RunManifestTimelineRebuildSource"),
                "运行清单须注册 TimelineRebuildSource（首个重建来源）");
    }

    @Test
    @DisplayName("投影：幂等（命中返回既有 id）+ 失败静默降级，不阻断主流程")
    void projectorIsIdempotentAndDegradesQuietly() throws IOException {
        String src = read(PROJECTOR);
        assertTrue(src.contains("findIdBySourceEvent"),
                "投影须按 sourceEventId 查重后再写（幂等）");
        assertTrue(src.contains("catch (RuntimeException"),
                "投影失败须静默降级（投影不是事实源，不得影响主流程）");
        assertTrue(src.contains("insertEntry") && src.contains("insertSubject"),
                "须写条目并落主体关联");
    }

    @Test
    @DisplayName("查询 SQL：ON CONFLICT 幂等、游标 (occurred_at,id)、标签与空间过滤")
    void mapperSqlGuardsIdempotencyCursorAndIsolation() throws IOException {
        String xml = read(MAPPER_XML);
        assertTrue(xml.contains("ON CONFLICT (source_event_id) DO NOTHING"),
                "写入须以 source_event_id 冲突即忽略（幂等）");
        assertTrue(xml.contains("e.occurred_at > #{q.cursorTs}") && xml.contains("e.id > #{q.cursorId}"),
                "游标分页须按 (occurred_at, id) 稳定推进（ADR-075 §4）");
        assertTrue(xml.contains("ORDER BY e.occurred_at ASC, e.id ASC"),
                "条目须按时间正序返回");
        assertTrue(xml.contains("LIMIT #{q.limit}"), "须限制每页条数");
        assertFalse(xml.contains("e.data_label IN"),
                "条目不因数据标签隐藏：超限只抹正文（见 DefaultTimelineQuery.visibleSummary）");
        assertTrue(xml.contains("e.workspace_id IS NULL OR e.workspace_id = #{q.workspaceId}"),
                "空间隔离须在 SQL 层显式过滤");

        String mapper = read(MAPPER_JAVA);
        assertTrue(mapper.contains("@InterceptorIgnore(tenantLine = \"true\")"),
                "投影可能无 Principal，须旁路 ScopeLine（空间隔离在服务层显式做）");
    }

    @Test
    @DisplayName("重建：查询侧按来源区间清除后重放，来源与写入共用同一映射")
    void rebuildSharesProjectionMapping() throws IOException {
        String query = read(QUERY);
        assertTrue(query.contains("deleteBySourceRange"),
                "重建须先按来源区间清除（ADR-075 §2）");
        assertTrue(query.contains("eventsInRange"),
                "重建须从来源取区间事件重放");
        assertTrue(query.contains("visibleSummary") && query.contains("atMost(clearance)"),
                "超限数据标签只抹正文（FE-00 §7.10 只显示标题），条目仍返回");

        String shared = read(SHARED_PROJECTION);
        assertTrue(shared.contains("toSourceEvent"),
                "写入与重建须共用同一映射（避免条目漂移）");
        String rebuild = read(REBUILD_SOURCE);
        assertTrue(rebuild.contains("implements TimelineRebuildSource"),
                "运行清单须实现 TimelineRebuildSource");
        assertTrue(rebuild.contains("RunManifestTimelineProjection"),
                "重建须复用共用映射，不得另写一份");
    }

    @Test
    @DisplayName("契约：事件 schema 覆盖 12 类 category；游标表登记 workspace 旁路白名单")
    void schemaAndExemptTableRegistered() throws IOException {
        String schema = read(EVENT_SCHEMA);
        for (String c : new String[]{"\"INBOUND\"", "\"RUN\"", "\"MODEL\"", "\"TOOL\"", "\"DECISION\"",
                "\"APPROVAL\"", "\"CHANGE\"", "\"OUTBOUND\"", "\"NOTIFY\"", "\"FEEDBACK\"",
                "\"CONFIG\"", "\"SECURITY\""}) {
            assertTrue(schema.contains(c), "事件 schema 缺少时间线类别：" + c);
        }
        assertTrue(schema.contains("\"category\""), "事件 schema 须以 category 为必填");

        String img = read(MQ_TOPICS);
        assertTrue(img.contains("TIMELINE_SOURCE = \"tioka.obs.timeline_source.v1\""),
                "tag 常量须与 schema 文件同名");

        String props = read(PERSISTENCE_PROPERTIES);
        assertTrue(props.contains("\"timeline_projection_cursor\""),
                "无 workspace 列的游标表须登记 workspace-scope-exempt-tables");
    }

    @Test
    @DisplayName("来源：台账 + Turn 内模型/工具各有映射与重建来源，四类 sourceType 齐备")
    void everySourceHasProjectionAndRebuildSource() throws IOException {
        String ext = read(EXT_PROJECTION);
        assertTrue(ext.contains("SOURCE_TYPE = \"ext_interaction\""),
                "台账映射须自述 source_type=ext_interaction（重建按此清除区间）");
        assertTrue(ext.contains("\"INBOUND\"") && ext.contains("\"NOTIFY\"") && ext.contains("\"OUTBOUND\""),
                "台账映射须能落 INBOUND / NOTIFY / OUTBOUND（ADR-075 §2）");
        assertTrue(ext.contains("GovernanceMqTopics.EXT_INTERACTION_RECORDED"),
                "来源事件名须取自 GovernanceMqTopics，禁止 biz 内硬编码");

        String item = read(ITEM_PROJECTION);
        assertTrue(item.contains("SOURCE_TYPE = \"runtime_item\""),
                "运行项映射须自述 source_type=runtime_item");
        assertTrue(item.contains("\"TOOL\"") && item.contains("TOOL_CALL") && item.contains("TOOL_RESULT"),
                "运行项映射须按步（turnId+step）合并工具调用与结果为 TOOL 条目");
        assertTrue(item.contains("GovernanceMqTopics.RUNTIME_ITEM_RECORDED"),
                "来源事件名须取自 GovernanceMqTopics，禁止 biz 内硬编码");

        String usage = read(USAGE_PROJECTION);
        assertTrue(usage.contains("SOURCE_TYPE = \"model_usage\""),
                "模型用量映射须自述 source_type=model_usage");
        assertTrue(usage.contains("\"MODEL\""), "模型用量映射须落 MODEL 类别");
        assertTrue(usage.contains("GovernanceMqTopics.MODEL_USAGE_RECORDED"),
                "来源事件名须取自 GovernanceMqTopics，禁止 biz 内硬编码");

        // 三类重建来源：实现接口 + 组件扫描 + 复用共用映射（写入与重建条目一致，不另写一份）
        assertRebuildSource(EXT_REBUILD, "ExtInteractionTimelineProjection");
        assertRebuildSource(ITEM_REBUILD, "RuntimeItemTimelineProjection");
        assertRebuildSource(USAGE_REBUILD, "ModelUsageTimelineProjection");

        // 只读端口：Turn 内模型/工具按时间段读回（供重建来源）
        assertTrue(read(ITEM_REPOSITORY).contains("listByRange(Instant from, Instant to)"),
                "ItemRepository 须提供按时间段读回运行项（OBS-08 只读端口）");
        assertTrue(read(USAGE_REPOSITORY).contains("listByRange(Instant from, Instant to)"),
                "ModelUsageRepository 须提供按时间段读回模型用量（OBS-08 只读端口）");
    }

    @Test
    @DisplayName("来源事件名：四类来源事件名常量齐备且取值固定（各自 payload schema 见 docs/contracts/events/）")
    void sourceEventTypeConstantsAreDeclared() throws IOException {
        String topics = read(MQ_TOPICS);
        assertTrue(topics.contains("RUN_MANIFEST_WRITTEN = \"tioka.obs.run_manifest_written.v1\""),
                "运行清单来源事件名须固定");
        assertTrue(topics.contains("EXT_INTERACTION_RECORDED = \"tioka.obs.ext_interaction_recorded.v1\""),
                "台账来源事件名须固定");
        assertTrue(topics.contains("RUNTIME_ITEM_RECORDED = \"tioka.obs.runtime_item_recorded.v1\""),
                "运行项来源事件名须固定");
        assertTrue(topics.contains("MODEL_USAGE_RECORDED = \"tioka.obs.model_usage_recorded.v1\""),
                "模型用量来源事件名须固定");
    }

    @Test
    @DisplayName("来源：审批单（runtime_approval）落 APPROVAL 条目，结局按状态映射（ADR-075 §2）")
    void approvalSourceProjectsApprovalEntries() throws IOException {
        String src = read(APPROVAL_PROJECTION);
        assertTrue(src.contains("SOURCE_TYPE = \"runtime_approval\""),
                "审批映射须自述 source_type=runtime_approval（重建按此清除区间）");
        assertTrue(src.contains("\"APPROVAL\""), "审批映射须落 APPROVAL 类别");
        assertTrue(src.contains("GovernanceMqTopics.APPROVAL_RECORDED"),
                "来源事件名须取自 GovernanceMqTopics，禁止硬编码");
        assertTrue(src.contains("\"WAITING\"") && src.contains("\"DENIED\"") && src.contains("\"ERROR\""),
                "结局须按 PENDING/REJECTED/EXPIRED 映射为 WAITING/DENIED/ERROR");
        assertTrue(src.contains("ApprovalStatus"), "状态映射须复用 ApprovalStatus 枚举，不得硬编码状态串");

        assertRebuildSource(APPROVAL_REBUILD, "RuntimeApprovalTimelineProjection");

        assertTrue(read(APPROVAL_REPOSITORY).contains("listByRange(Instant from, Instant to)"),
                "ApprovalRepository 须提供按时间段读回审批（OBS-08 只读端口）");
        assertTrue(read(APPROVAL_MAPPER_XML).contains("id=\"listByRange\""),
                "RuntimeApprovalMapper 须提供 listByRange 区间查询");
        assertTrue(read(MQ_TOPICS).contains("APPROVAL_RECORDED = \"tioka.obs.approval_recorded.v1\""),
                "审批来源事件名须固定");
    }

    @Test
    @DisplayName("来源：规则求值（orch_decision）落 DECISION 条目，主体经 orch_event 补齐（ADR-075 §6）")
    void decisionSourceProjectsDecisionEntries() throws IOException {
        String src = read(DECISION_PROJECTION);
        assertTrue(src.contains("SOURCE_TYPE = \"orch_decision\""),
                "规则求值映射须自述 source_type=orch_decision（重建按此清除区间）");
        assertTrue(src.contains("\"DECISION\""), "规则求值映射须落 DECISION 类别");
        assertTrue(src.contains("GovernanceMqTopics.DECISION_RECORDED"),
                "来源事件名须取自 GovernanceMqTopics，禁止硬编码");
        assertTrue(src.contains("\"DENIED\"") && src.contains("\"ERROR\"") && src.contains("\"OK\""),
                "结局须按 ACCEPT/WOULD_ACCEPT/IGNORE→OK、STOP→DENIED、UNAVAILABLE→ERROR 映射");
        assertTrue(src.contains("DecisionCode"), "决定码映射须复用 DecisionCode 枚举，不得硬编码状态串");

        assertRebuildSource(DECISION_REBUILD, "OrchDecisionTimelineProjection");

        String xml = read(DECISION_MAPPER_XML);
        assertTrue(xml.contains("id=\"listByRange\""), "OrchDecisionMapper 须提供 listByRange 区间查询");
        assertTrue(xml.contains("LEFT JOIN orch_event"),
                "决定表无主体列，须左连 orch_event 补 subject_key / workspace_id");
        assertTrue(read(MQ_TOPICS).contains("DECISION_RECORDED = \"tioka.obs.decision_recorded.v1\""),
                "规则求值来源事件名须固定");
    }

    @Test
    @DisplayName("来源：用户反馈（eval_feedback）落 FEEDBACK 条目，主体固定为 turn:<turnId>（ADR-075 §2）")
    void feedbackSourceProjectsFeedbackEntries() throws IOException {
        String src = read(FEEDBACK_PROJECTION);
        assertTrue(src.contains("SOURCE_TYPE = \"eval_feedback\""),
                "反馈映射须自述 source_type=eval_feedback（重建按此清除区间）");
        assertTrue(src.contains("\"FEEDBACK\""), "反馈映射须落 FEEDBACK 类别");
        assertTrue(src.contains("GovernanceMqTopics.FEEDBACK_RECORDED"),
                "来源事件名须取自 GovernanceMqTopics，禁止硬编码");
        assertTrue(src.contains("\"turn\""),
                "反馈表无空间与请求列（workspace-scope-exempt），主体须固定为 turn:<turnId>");
        assertTrue(src.contains("点赞") && src.contains("点踩"), "标题须区分点赞 / 点踩");
        assertTrue(src.contains("\"UNDO\"") && src.contains("\"REVIEW\""),
                "撤销（TRU-07）与法务驳回来源须在标题上区分");

        assertRebuildSource(FEEDBACK_REBUILD, "EvalFeedbackTimelineProjection");

        assertTrue(read(FEEDBACK_REPOSITORY).contains("listByRange(Instant from, Instant to)"),
                "EvalFeedbackRepository 须提供按时间段读回反馈（OBS-08 只读端口）");
        assertTrue(read(MQ_TOPICS).contains("FEEDBACK_RECORDED = \"tioka.obs.feedback_recorded.v1\""),
                "反馈来源事件名须固定");
    }

    @Test
    @DisplayName("来源：变更动作（trust_action）落 CHANGE 条目，撤销 / 补偿按状态映射（ADR-075 §2）")
    void changeSourceProjectsChangeEntries() throws IOException {
        String src = read(CHANGE_PROJECTION);
        assertTrue(src.contains("SOURCE_TYPE = \"trust_action\""),
                "变更映射须自述 source_type=trust_action（重建按此清除区间）");
        assertTrue(src.contains("\"CHANGE\""), "变更映射须落 CHANGE 类别");
        assertTrue(src.contains("GovernanceMqTopics.CHANGE_RECORDED"),
                "来源事件名须取自 GovernanceMqTopics，禁止硬编码");
        assertTrue(src.contains("\"WAITING\"") && src.contains("\"OK\"") && src.contains("\"ERROR\""),
                "结局须按 PROPOSED/COMPENSATING→WAITING、APPROVED/APPLIED/COMPENSATED→OK、"
                        + "COMPENSATION_FAILED→ERROR 映射");
        assertTrue(src.contains("PreparedCallStore"),
                "状态映射须复用 PreparedCallStore 状态常量，不得硬编码状态串");
        assertTrue(src.contains("撤销"), "撤销 / 补偿动作的标题须与变更提交区分");
        assertTrue(src.contains("turn.getRequestId()"),
                "trust_action 无 request_id 列，关联键须经 runtime_turn 补齐");

        assertRebuildSource(CHANGE_REBUILD, "TrustActionTimelineProjection");

        assertTrue(read(CHANGE_REPOSITORY).contains("listByRange(Instant from, Instant to)"),
                "TrustActionRepository 须提供按时间段读回变更动作（OBS-08 只读端口）");
        assertTrue(read(CHANGE_MAPPER_XML).contains("id=\"listByRange\""),
                "TrustActionMapper 须提供 listByRange 区间查询");
        assertTrue(read(MQ_TOPICS).contains("CHANGE_RECORDED = \"tioka.obs.change_recorded.v1\""),
                "变更来源事件名须固定");
    }

    @Test
    @DisplayName("来源：配置变更（cfg_change）落 CONFIG 背景条目，主体取配置对象本身（ADR-075 §2/§5）")
    void configSourceProjectsConfigEntries() throws IOException {
        String src = read(CONFIG_PROJECTION);
        assertTrue(src.contains("SOURCE_TYPE = \"cfg_change\""),
                "配置变更映射须自述 source_type=cfg_change（重建按此清除区间）");
        assertTrue(src.contains("\"CONFIG\""), "配置变更映射须落 CONFIG 类别");
        assertTrue(src.contains("GovernanceMqTopics.CONFIG_RECORDED"),
                "来源事件名须取自 GovernanceMqTopics，禁止硬编码");
        assertTrue(src.contains("replace('_', '.')"),
                "配置对象类型须归一为小写点分名（ADR-075 §5 例：model.alias:sys.chat）");
        assertTrue(src.contains("row.getTargetKey()"), "主体标识须取配置对象 target_key");
        assertTrue(src.contains("\"publish\"") && src.contains("\"rollback\""),
                "标题须按 after_json.action 区分发布 / 回滚");

        String rebuild = read(CONFIG_REBUILD);
        assertTrue(rebuild.contains("implements TimelineRebuildSource"),
                "配置变更重建来源须实现 TimelineRebuildSource（ADR-075 §2）");
        assertTrue(rebuild.contains("CfgChangeTimelineProjection"),
                "重建须复用共用映射，不得另写一份");
        assertTrue(rebuild.contains("SOURCE_TYPE"),
                "重建来源的 sourceType 须取自共用映射常量，不得另写字面量");

        assertTrue(read(CONFIG_MAPPER_JAVA).contains("listByRange"),
                "CfgChangeMapper 须提供按时间段读回配置变更（OBS-08 只读端口）");
        assertTrue(read(CONFIG_MAPPER_XML).contains("id=\"listByRange\""),
                "CfgChangeMapper 须提供 listByRange 区间查询");
        assertTrue(read(AUTO_CONFIG).contains("CfgChangeTimelineRebuildSource"),
                "时间线装配须注册配置变更重建来源（governance-biz 不在全局组件扫描内）");
        assertTrue(read(MQ_TOPICS).contains("CONFIG_RECORDED = \"tioka.obs.config_recorded.v1\""),
                "配置变更来源事件名须固定");
    }

    @Test
    @DisplayName("来源：安全类审计事件（audit_event）落 SECURITY 条目，动作白名单与目录一致（ADR-075 §2）")
    void securitySourceProjectsSecurityEntries() throws IOException {
        String src = read(SECURITY_PROJECTION);
        assertTrue(src.contains("SOURCE_TYPE = \"audit_event\""),
                "安全映射须自述 source_type=audit_event（ADR-075 §2 来源列举，重建按此清除区间）");
        assertTrue(src.contains("\"SECURITY\""), "审计来源映射须落 SECURITY 类别");
        assertTrue(src.contains("GovernanceMqTopics.SECURITY_RECORDED"),
                "来源事件名须取自 GovernanceMqTopics，禁止硬编码");
        assertTrue(src.contains("SecurityAuditActions.isSecurity("),
                "只投影动作目录中 SECURITY 类动作，避免与其它来源重复计数");
        assertTrue(src.contains("\"SUCCESS\"") && src.contains("\"DENIED\"") && src.contains("\"ERROR\""),
                "结局须按审计 SUCCESS/DENIED/ERROR 映射（ADR-017）");
        assertTrue(src.contains("row.getSubject()"),
                "主体须优先取审计记录上的 subject（ADR-073 §2），无则回落资源引用");
        assertTrue(src.contains("row.getResourceType()") && src.contains("row.getResourceId()"),
                "无 subject 时须回落 resource_type:resource_id，使管理类动作在主体时间线可查");

        assertRebuildSource(SECURITY_REBUILD, "AuditEventTimelineProjection");

        assertTrue(read(SECURITY_MAPPER_JAVA).contains("listByActionsInRange"),
                "AuditEventMapper 须提供按动作集合 + 时间段读回审计事件（OBS-08 只读端口）");
        assertTrue(read(SECURITY_MAPPER_XML).contains("id=\"listByActionsInRange\""),
                "AuditEventMapper 须提供 listByActionsInRange 区间查询");
        assertTrue(read(SECURITY_REPOSITORY).contains(
                        "listByActionsInRange(List<String> actions, Instant from, Instant to)"),
                "AuditEventRepository 须暴露只读端口（application 层禁止直持 Mapper）");
        assertTrue(read(MQ_TOPICS).contains("SECURITY_RECORDED = \"tioka.obs.security_recorded.v1\""),
                "安全来源事件名须固定");

        assertArrayEquals(
                securityActionsFromYaml().toArray(String[]::new),
                javaActionsOf(read(SECURITY_ACTIONS)).toArray(String[]::new),
                "SecurityAuditActions 白名单须与 docs/contracts/audit/actions.yaml 的 SECURITY 动作逐一一致");
    }

    @Test
    @DisplayName("覆盖：ADR-075 §2 的 12 类 category 均有写入点投影（合同全链投影收口）")
    void allTwelveCategoriesHaveProjection() throws IOException {
        String[][] expected = {
                {"INBOUND", EXT_PROJECTION},
                {"RUN", SHARED_PROJECTION},
                {"MODEL", USAGE_PROJECTION},
                {"TOOL", ITEM_PROJECTION},
                {"DECISION", DECISION_PROJECTION},
                {"APPROVAL", APPROVAL_PROJECTION},
                {"CHANGE", CHANGE_PROJECTION},
                {"OUTBOUND", EXT_PROJECTION},
                {"NOTIFY", EXT_PROJECTION},
                {"FEEDBACK", FEEDBACK_PROJECTION},
                {"CONFIG", CONFIG_PROJECTION},
                {"SECURITY", SECURITY_PROJECTION},
        };
        for (String[] e : expected) {
            assertTrue(read(e[1]).contains("\"" + e[0] + "\""),
                    "ADR-075 §2 的 " + e[0] + " 类缺少写入点投影（" + e[1] + "）");
        }
    }

    @Test
    @DisplayName("写入即投影：Turn 内 MODEL / TOOL 在各自写入点经 TimelineProjector 投影（OBS-08 偏离收口）")
    void turnModelAndToolProjectAtWriteTime() throws IOException {
        String sink = read(ITEM_SINK);
        assertTrue(sink.contains("TimelineProjector"),
                "运行项写入点须经 TimelineProjector 投影 TOOL（写入即投影，不再仅依赖按时间段重建）");
        assertTrue(sink.contains("RuntimeItemTimelineProjection"),
                "写入点须复用共用映射，不得另写一份（避免条目漂移）");
        assertTrue(sink.contains("projectTool"),
                "工具结果落库后须触发 TOOL 投影");
        assertTrue(sink.contains("items.listByTurn(ctx.turnId())"),
                "TOOL 条目须按 turnId + step 合并调用与结果，与重建同源");

        String usage = read(USAGE_RECORDER);
        assertTrue(usage.contains("TimelineProjector"),
                "用量写入点须经 TimelineProjector 投影 MODEL（写入即投影）");
        assertTrue(usage.contains("ModelUsageTimelineProjection"),
                "写入点须复用共用映射，不得另写一份");
        assertTrue(usage.contains("project(batch);"),
                "批量落库成功后须逐行投影 MODEL 条目");

        assertTrue(read(ITEM_PROJECTION)
                        .contains("toSourceEvent(List<RuntimeItemDO> items, String turnId, Integer step"),
                "单步取事件须由共用映射暴露，保证写入与重建条目一致");
    }

    /* ---------- 工具 ---------- */

    /** 从动作目录（登记制契约）抽出 {@code category: SECURITY} 的动作名。 */
    private static List<String> securityActionsFromYaml() throws IOException {
        List<String> out = new ArrayList<>();
        String pending = null;
        for (String line : read(AUDIT_ACTIONS_YAML).split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- action:")) {
                pending = trimmed.substring("- action:".length()).trim();
            } else if (pending != null && "category: SECURITY".equals(trimmed)) {
                out.add(pending);
                pending = null;
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    /** 从 Java 源码抽出 {@code "action.name"} 形式的字面量（白名单动作名）。 */
    private static List<String> javaActionsOf(String source) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"([a-z][a-z0-9_]*\\.[a-z0-9_.]+)\"")
                .matcher(source);
        while (m.find()) {
            if (!out.contains(m.group(1))) {
                out.add(m.group(1));
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    private static void assertRebuildSource(String path, String projectionClass) throws IOException {
        String src = read(path);
        assertTrue(src.contains("implements TimelineRebuildSource"),
                path + " 须实现 TimelineRebuildSource（ADR-075 §2）");
        assertTrue(src.contains("@Component"),
                path + " 须为可被组件扫描的 @Component（由 TimelineAutoConfiguration 收集）");
        assertTrue(src.contains(projectionClass),
                path + " 须复用共用映射 " + projectionClass + "（避免写入与重建条目漂移）");
        assertTrue(src.contains("SOURCE_TYPE"),
                path + " 的 sourceType 须取自共用映射常量，不得另写字面量");
    }

    private static Path findRepoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path cur = dir; cur != null; cur = cur.getParent()) {
            if (Files.isDirectory(cur.resolve("tioka-modules"))
                    && Files.isDirectory(cur.resolve("docs"))) {
                return cur;
            }
        }
        throw new IllegalStateException(
                "无法从 " + dir + " 向上找到仓库根（需同时含 tioka-modules 与 docs）");
    }

    private static String read(String relative) throws IOException {
        Path file = repoRoot.resolve(relative);
        assertTrue(Files.isRegularFile(file), "缺少源文件：" + file);
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
