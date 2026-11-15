package com.tioka.server.gov;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3-B27（OBS-07）：外部交互台账扩展源码守护（离线、纯文件断言，做法同
 * {@link M2Obs04aLedgerContractTest}）。
 *
 * <p>守护 ADR-074 §2 的写入点扩展（通知 / 数据源 / MCP / 非 Turn 模型调用）与 §4 的查询与统计
 * （明细 / 数据去向 / 调用统计），并守护施工设计「只加写入调用与 {@code kind}，不改台账结构」——
 * 台账表结构只在 V220 建立，V184 号位作废，纯查询实现免新迁移。
 * 端到端语义（真实 PG 写入与聚合）由 CI 的 IT 承接。
 */
class M3Obs07LedgerContractTest {

    private static Path repoRoot;

    private static final String SYSTEM_BIZ_MAIN =
            "tioka-modules/tioka-module-system/tioka-module-system-biz/src/main";
    private static final String SYSTEM_API_MAIN =
            "tioka-modules/tioka-module-system/tioka-module-system-api/src/main/java";

    /* OBS-07 写入点 */
    private static final String NOTIFY =
            "tioka-modules/tioka-module-infra/tioka-module-infra-biz/src/main/java"
                    + "/com/tioka/infra/application/notify/NtfDeliveryConsumer.java";
    private static final String DATASOURCE =
            "tioka-modules/tioka-module-knowledge/tioka-module-knowledge-biz/src/main/java"
                    + "/com/tioka/knowledge/application/DataSourceService.java";
    private static final String MCP =
            "tioka-modules/tioka-module-mcp/tioka-module-mcp-biz/src/main/java"
                    + "/com/tioka/mcp/application/McpAdminService.java";
    private static final String MODEL =
            "tioka-modules/tioka-module-model/tioka-module-model-biz/src/main/java"
                    + "/com/tioka/model/application/gateway/DefaultModelGateway.java";

    /* OBS-07 查询出口 */
    private static final String QUERY_API =
            SYSTEM_API_MAIN + "/com/tioka/system/api/obs/ExtInteractionQueryApi.java";
    private static final String MAPPER_XML = SYSTEM_BIZ_MAIN + "/resources/mapper/ExtInteractionMapper.xml";
    private static final String QUERY_SERVICE =
            SYSTEM_BIZ_MAIN + "/java/com/tioka/system/application/obs/ExtInteractionQueryService.java";
    private static final String CONTROLLER =
            SYSTEM_BIZ_MAIN + "/java/com/tioka/system/api/ExtInteractionController.java";

    @BeforeAll
    static void resolveRepoRoot() {
        repoRoot = findRepoRoot();
    }

    @Test
    @DisplayName("写入点：通知 / 数据源 / MCP / 非 Turn 模型调用各写正确 kind 的台账")
    void writePointsRecordTheirKinds() throws IOException {
        String notify = read(NOTIFY);
        assertTrue(notify.contains("Direction.OUT") && notify.contains("\"notify\""),
                "通知投递须记 OUT notify 台账");
        assertTrue(notify.contains("getIfAvailable()"), "台账可选注入，缺失时静默");

        String datasource = read(DATASOURCE);
        assertTrue(datasource.contains("Direction.IN") && datasource.contains("\"datasource\""),
                "数据源同步批次须记 IN datasource 台账（每批一条）");
        assertTrue(datasource.contains("getIfAvailable()"), "台账可选注入，缺失时静默");

        String mcp = read(MCP);
        assertTrue(mcp.contains("Direction.OUT") && mcp.contains("\"mcp\""),
                "MCP 远端调用须记 OUT mcp 台账");
        assertTrue(mcp.contains("getIfAvailable()"), "台账可选注入，缺失时静默");

        String model = read(MODEL);
        assertTrue(model.contains("\"model\""), "非 Turn 云端模型调用须记 model 台账");
        assertTrue(model.contains("ctx.turnId() != null"),
                "model 台账只针对不在 Turn 内的调用（Turn 内由 model_usage 覆盖）");
        assertTrue(model.contains("getIfAvailable()"), "台账可选注入，缺失时静默");
    }

    @Test
    @DisplayName("查询接口：明细 / 数据去向 / 统计三出口 + 关联键查询条件（ADR-074 §4）")
    void queryApiExposesSectionFourSurfaces() throws IOException {
        String src = read(QUERY_API);
        assertTrue(src.contains("List<ExtInteractionView> query("), "须提供明细查询出口");
        assertTrue(src.contains("destinationReport("), "须提供数据去向报表出口");
        assertTrue(src.contains("counterpartyStats("), "须提供按对方与 kind 的调用统计出口");
        for (String key : new String[]{"requestId", "subject", "counterparty", "externalRef", "kind"}) {
            assertTrue(src.contains(key), "查询条件缺少 ADR-074 §4 维度：" + key);
        }
    }

    @Test
    @DisplayName("明细 SQL：外部回执号 / 主体 / 对方均可反查，时间范围过滤，驼峰别名加引号")
    void querySqlFiltersByLedgerKeys() throws IOException {
        String xml = read(MAPPER_XML);
        assertTrue(xml.contains("external_ref = #{externalRef}"),
                "须支持「输入对方单号，找到我方全过程」（external_ref 反查）");
        assertTrue(xml.contains("subject = #{subject}"), "须支持按 subject 列出去向");
        assertTrue(xml.contains("counterparty = #{counterparty}"), "须支持按对方过滤");
        assertTrue(xml.contains("ts >= #{from}") && xml.contains("ts <= #{to}"), "须支持时间段查询");
        assertTrue(xml.contains("ORDER BY ts DESC"), "明细须按时间倒序");
        assertTrue(xml.contains("request_id AS \"requestId\""),
                "驼峰列别名必须加双引号，否则 HashMap 取键恒为 null");
    }

    @Test
    @DisplayName("数据去向报表：只统计出站，按 data_label × kind × counterparty 聚合")
    void destinationReportAggregatesOutboundByLabel() throws IOException {
        String xml = read(MAPPER_XML);
        assertTrue(xml.contains("destinationReport"), "须有数据去向报表 SQL");
        assertTrue(xml.contains("data_label AS \"dataLabel\""), "报表须以数据标签为维度");
        assertTrue(xml.contains("GROUP BY kind, counterparty, data_label"),
                "报表须按 kind × counterparty × data_label 聚合");
        assertTrue(xml.contains("direction = 'OUT'"), "数据去向只算出站（OUT）");
        assertTrue(xml.contains("percentile_cont(0.95)"),
                "调用统计须给 P95 时延（ADR-074 §4）");
    }

    @Test
    @DisplayName("管控端点：平台级管理员可查，台账无租户列故禁止工作空间级越权")
    void controllerGuardsPlatformScope() throws IOException {
        String src = read(CONTROLLER);
        assertTrue(src.contains("/api/system/ext-interaction"), "端点前缀固定");
        assertTrue(src.contains("Permissions.AUDIT_READ"), "须以 audit:read 授权");
        assertTrue(src.contains("requirePlatformAdmin()") && src.contains("ErrorCode.FORBIDDEN"),
                "台账为全局观测面，非平台级管理员须被拒");
    }

    /** 改动台账结构的 DDL（注释里提及表名不算）。 */
    private static final Pattern LEDGER_DDL = Pattern.compile(
            "(?i)(alter|create|drop)\\s+table\\s+(if\\s+not\\s+exists\\s+)?ext_interaction"
                    + "|alter\\s+table\\s+ext_interaction");

    @Test
    @DisplayName("不改台账结构：ext_interaction 只在 V220 建表，后续迁移不得改动其 DDL")
    void ledgerStructureUntouchedAfterV220() throws IOException {
        Path migrations = repoRoot.resolve("tioka-server/src/main/resources/db/migration");
        assertTrue(Files.isDirectory(migrations), "缺少迁移目录：" + migrations);
        List<String> offenders;
        try (Stream<Path> files = Files.list(migrations)) {
            offenders = files
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .filter(p -> !p.getFileName().toString().startsWith("V220__"))
                    .filter(p -> {
                        try {
                            return LEDGER_DDL.matcher(
                                    Files.readString(p, StandardCharsets.UTF_8)).find();
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        }
        assertTrue(offenders.isEmpty(),
                "OBS-07 只加写入调用与 kind，不改台账结构；以下迁移改动了 ext_interaction：" + offenders);
        String service = read(QUERY_SERVICE);
        assertTrue(service.contains("implements ExtInteractionQueryApi"),
                "查询实现须落在 system-biz 的 obs 包");
    }

    /* ---------- 工具 ---------- */

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
