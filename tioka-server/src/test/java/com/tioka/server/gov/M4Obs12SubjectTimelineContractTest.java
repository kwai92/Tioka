package com.tioka.server.gov;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-12：Agent / 知识文档 / 用户三类主体时间线的源码守护（离线、纯文件断言，做法同
 * {@link M3Obs08TimelineContractTest}）。
 *
 * <p>守护 ADR-075 §4「M4 扩展：Agent 主体时间线、知识文档主体时间线、用户主体时间线」：
 * 三类主体各有来源映射 + 重建来源（{@code TimelineRebuildSource}），重建复用同一映射；
 * 只读端口经 Mapper 的 {@code listByRange} 读回，空间隔离在查询侧做。
 * 端到端语义（真实 PG 写入、投影、重建）由 CI 的 IT 承接。
 */
class M4Obs12SubjectTimelineContractTest {

    private static final String KNOWLEDGE_BIZ =
            "tioka-modules/tioka-module-knowledge/tioka-module-knowledge-biz";
    private static final String AGENT_BIZ =
            "tioka-modules/tioka-module-agent/tioka-module-agent-biz";
    private static final String SYSTEM_BIZ =
            "tioka-modules/tioka-module-system/tioka-module-system-biz";

    private static final String KB_PROJECTION = KNOWLEDGE_BIZ
            + "/src/main/java/com/tioka/knowledge/application/timeline/KnowledgeDocumentTimelineProjection.java";
    private static final String KB_REBUILD = KNOWLEDGE_BIZ
            + "/src/main/java/com/tioka/knowledge/application/timeline/KnowledgeDocumentTimelineRebuildSource.java";
    private static final String KB_REPOSITORY = KNOWLEDGE_BIZ
            + "/src/main/java/com/tioka/knowledge/infrastructure/KnowledgeRepository.java";
    private static final String KB_MAPPER_JAVA = KNOWLEDGE_BIZ
            + "/src/main/java/com/tioka/knowledge/infrastructure/mapper/KbDocumentMapper.java";
    private static final String KB_MAPPER_XML = KNOWLEDGE_BIZ + "/src/main/resources/mapper/KbDocumentMapper.xml";
    private static final String KB_TIMELINE_ROW = KNOWLEDGE_BIZ
            + "/src/main/java/com/tioka/knowledge/infrastructure/persistence/KbDocumentTimelineDO.java";
    private static final String KB_POM = KNOWLEDGE_BIZ + "/pom.xml";

    private static final String AGENT_PROJECTION = AGENT_BIZ
            + "/src/main/java/com/tioka/agent/application/timeline/AgentTimelineProjection.java";
    private static final String AGENT_REBUILD = AGENT_BIZ
            + "/src/main/java/com/tioka/agent/application/timeline/AgentTimelineRebuildSource.java";
    private static final String AGENT_REPOSITORY = AGENT_BIZ
            + "/src/main/java/com/tioka/agent/infrastructure/AgentRepository.java";
    private static final String AGENT_MAPPER_JAVA = AGENT_BIZ
            + "/src/main/java/com/tioka/agent/infrastructure/mapper/AgentVersionMapper.java";
    private static final String AGENT_MAPPER_XML = AGENT_BIZ + "/src/main/resources/mapper/AgentVersionMapper.xml";
    private static final String AGENT_TIMELINE_ROW = AGENT_BIZ
            + "/src/main/java/com/tioka/agent/infrastructure/persistence/AgentVersionTimelineDO.java";

    private static final String USER_PROJECTION = SYSTEM_BIZ
            + "/src/main/java/com/tioka/system/application/obs/UserTimelineProjection.java";
    private static final String USER_REBUILD = SYSTEM_BIZ
            + "/src/main/java/com/tioka/system/application/obs/UserTimelineRebuildSource.java";
    private static final String USER_REPOSITORY = SYSTEM_BIZ
            + "/src/main/java/com/tioka/system/infrastructure/AuthUserRepository.java";
    private static final String USER_REPOSITORY_IMPL = SYSTEM_BIZ
            + "/src/main/java/com/tioka/system/infrastructure/AuthUserRepositoryImpl.java";

    private static final String MQ_TOPICS =
            "tioka-modules/tioka-module-governance/tioka-module-governance-api/src/main/java"
                    + "/com/tioka/governance/api/mq/GovernanceMqTopics.java";
    private static final String CONSTRUCTION_DOC =
            "docs/architecture/construction/M4-06-企业交付与审计.md";
    private static final String SUBJECT_TIMELINE_IT =
            "tioka-server/src/test/java/com/tioka/server/gov/M4Obs12SubjectTimelineIT.java";

    private static Path repoRoot;

    @BeforeAll
    static void resolveRepoRoot() {
        repoRoot = findRepoRoot();
    }

    @Test
    @DisplayName("三类主体：Agent / 知识文档 / 用户各有来源映射，主体类型与类别固定（ADR-075 §4）")
    void threeSubjectProjectionsAreDeclared() throws IOException {
        assertProjection(KB_PROJECTION, "kb_document", "knowledge_doc");
        assertProjection(AGENT_PROJECTION, "agent_version", "agent");
        assertProjection(USER_PROJECTION, "auth_user", "user");
    }

    @Test
    @DisplayName("重建来源：三类主体各实现 TimelineRebuildSource 并复用共用映射（@Component 组件扫描收集）")
    void eachSubjectHasRebuildSourceReusingProjection() throws IOException {
        assertRebuildSource(KB_REBUILD, "KnowledgeDocumentTimelineProjection");
        assertRebuildSource(AGENT_REBUILD, "AgentTimelineProjection");
        assertRebuildSource(USER_REBUILD, "UserTimelineProjection");
    }

    @Test
    @DisplayName("只读端口：三类主体各自提供按时间段读回（application 不直持 Mapper）")
    void repositoriesExposeRangePorts() throws IOException {
        assertTrue(read(KB_REPOSITORY).contains("listByRange(Instant from, Instant to)"),
                "KnowledgeRepository 须提供按时间段读回文档（OBS-12 只读端口）");
        assertTrue(read(AGENT_REPOSITORY).contains("listByRange(Instant from, Instant to)"),
                "AgentRepository 须提供按时间段读回版本（OBS-12 只读端口）");
        assertTrue(read(USER_REPOSITORY).contains("listByRange(Instant from, Instant to)"),
                "AuthUserRepository 须提供按时间段读回账号（OBS-12 只读端口）");
        assertTrue(read(USER_REPOSITORY_IMPL).contains("listByRange(Instant from, Instant to)"),
                "AuthUserRepositoryImpl 须实现按时间段读回账号");
    }

    @Test
    @DisplayName("区间查询：Agent / 知识文档经 listByRange 左连补空间，重建无 Principal 须旁路 ScopeLine")
    void mappersExposeRangeQueryWithSpaceJoin() throws IOException {
        String kbMapper = read(KB_MAPPER_JAVA);
        assertTrue(kbMapper.contains("@InterceptorIgnore(tenantLine = \"true\")"),
                "KbDocumentMapper.listByRange 须旁路 ScopeLine（异步重建无 Principal）");
        assertTrue(kbMapper.contains("listByRange"), "KbDocumentMapper 须提供 listByRange");
        String kbXml = read(KB_MAPPER_XML);
        assertTrue(kbXml.contains("id=\"listByRange\""), "KbDocumentMapper.xml 须含 listByRange 查询");
        assertTrue(kbXml.contains("LEFT JOIN kb_base"),
                "kb_document 无 workspace_id 列，须左连 kb_base 补齐空间（否则投影条目空间为空）");

        String agentMapper = read(AGENT_MAPPER_JAVA);
        assertTrue(agentMapper.contains("@InterceptorIgnore(tenantLine = \"true\")"),
                "AgentVersionMapper.listByRange 须旁路 ScopeLine（异步重建无 Principal）");
        assertTrue(agentMapper.contains("listByRange"), "AgentVersionMapper 须提供 listByRange");
        String agentXml = read(AGENT_MAPPER_XML);
        assertTrue(agentXml.contains("id=\"listByRange\""), "AgentVersionMapper.xml 须含 listByRange 查询");
        assertTrue(agentXml.contains("LEFT JOIN agent_agent"),
                "agent_version 无 workspace_id 列，须左连 agent_agent 补齐空间");
    }

    @Test
    @DisplayName("读回行：Agent / 知识文档时间线读回行不带 @TableName，不参与 ScopeLine 表扫描")
    void timelineRowsAreNotScopedEntities() throws IOException {
        assertFalse(read(KB_TIMELINE_ROW).contains("com.baomidou.mybatisplus.annotation.TableName"),
                "KbDocumentTimelineDO 为读回行，不参与 ScopeLine 表扫描（同 AuditEventTimelineDO）");
        assertFalse(read(AGENT_TIMELINE_ROW).contains("com.baomidou.mybatisplus.annotation.TableName"),
                "AgentVersionTimelineDO 为读回行，不参与 ScopeLine 表扫描");
    }

    @Test
    @DisplayName("依赖：knowledge-biz 声明 governance-api（时间线来源事件与重建 SPI）")
    void knowledgeModuleDeclaresGovernanceApiDependency() throws IOException {
        assertTrue(read(KB_POM).contains("tioka-module-governance-api"),
                "知识模块须依赖 governance-api（TimelineSourceEvent / TimelineRebuildSource / GovernanceMqTopics）");
    }

    @Test
    @DisplayName("用户来源：取自账号表 auth_user，区分用户 / 服务账号并标注停用态（ADR-075 §4 管理员调查）")
    void userSourceReadsAccountTable() throws IOException {
        String src = read(USER_PROJECTION);
        assertTrue(src.contains("getDefaultWorkspaceId"),
                "用户条目空间取账号 default_workspace_id（auth_user 无 workspace_id 列）");
        assertTrue(src.contains("SERVICE") && src.contains("服务账号"),
                "标题须区分用户与服务账号（auth_user.type = USER / SERVICE）");
        assertTrue(src.contains("getEnabled") && src.contains("已停用"),
                "停用账号须在标题标注");
    }

    @Test
    @DisplayName("事件常量：三类主题复用既有 CONFIG_RECORDED，不新增来源事件 schema（EnvelopeContractTest 约束）")
    void subjectSourcesReuseExistingEventConstant() throws IOException {
        assertTrue(read(MQ_TOPICS).contains("CONFIG_RECORDED = \"tioka.obs.config_recorded.v1\""),
                "三类主体来源事件名须复用既有 CONFIG_RECORDED 常量");
        for (String path : List.of(KB_PROJECTION, AGENT_PROJECTION, USER_PROJECTION)) {
            assertTrue(read(path).contains("GovernanceMqTopics.CONFIG_RECORDED"),
                    path + " 须取事件名自 GovernanceMqTopics，禁止硬编码");
        }
    }

    @Test
    @DisplayName("端到端：CI 的 IT 覆盖三类主体经 rebuild 后在主体时间线查到 CONFIG")
    void subjectTimelineItCoversThreeSubjects() throws IOException {
        String it = read(SUBJECT_TIMELINE_IT);
        assertTrue(it.contains("rebuild("), "IT 须经 timeline.rebuild 重建投影");
        for (String subject : List.of("\"agent\"", "\"knowledge_doc\"", "\"user\"")) {
            assertTrue(it.contains("pageBySubject(" + subject),
                    "IT 须覆盖主体 " + subject + " 的主体时间线查询");
        }
        assertTrue(it.contains("TimelineCategory.CONFIG"), "IT 须断言 CONFIG 条目");
    }

    @Test
    @DisplayName("文档：M4-06 §7 回写三类主体时间线已落地，仅余前端 FE-47")
    void constructionDocRecordsLandedSubjects() throws IOException {
        String doc = read(CONSTRUCTION_DOC);
        assertTrue(doc.contains("M4Obs12SubjectTimelineContractTest"),
                "交付文档须登记三类主体时间线的离线守护测试");
        assertTrue(doc.contains("M4Obs12SubjectTimelineIT"),
                "交付文档须登记三类主体时间线的端到端 IT");
        assertTrue(doc.contains("FE-47"),
                "交付文档须保留前端 FE-47 未落地项");
    }

    /* ---------- 工具 ---------- */

    private static void assertProjection(String path, String sourceType, String subjectType) throws IOException {
        String src = read(path);
        assertTrue(src.contains("SOURCE_TYPE = \"" + sourceType + "\""),
                path + " 须自述 source_type=" + sourceType + "（重建按此清除区间）");
        assertTrue(src.contains("SUBJECT_TYPE = \"" + subjectType + "\""),
                path + " 须自述主体类型 " + subjectType + "（ADR-075 §4）");
        assertTrue(src.contains("\"CONFIG\""), path + " 须落 CONFIG 类别");
        assertTrue(src.contains("toSourceEvent"), path + " 须提供 toSourceEvent 映射");
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
