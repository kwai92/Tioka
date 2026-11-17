package com.tioka.server.gov;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3-B36（OBS-09）：审计的审计 / 权限点细分 / 关联键与游标查询的源码守护（离线、纯文件断言，
 * 做法同 {@link M3Obs08TimelineContractTest} / {@link AuditedActionCatalogTest}）。
 *
 * <p>守护 ADR-072 §6：查询与导出本身都写审计（分类 {@code DATA}，导出记录条件、行数与文件哈希）；
 * 权限点细分为 {@code audit:read}（本空间）/ {@code audit:read:all}（全局）/ {@code audit:export}；
 * 查询接口增加 {@code trace_id}/{@code request_id}/{@code subject}/{@code turn_id}/{@code run_id}
 * 条件（ADR-073 §1），分页改为 {@code (ts, id)} 游标。端到端语义（真实 PG 写入、游标续查、
 * 导出留痕可查回）由 CI 的 IT 承接。
 */
class M3Obs09AuditQueryContractTest {

    private static Path repoRoot;

    private static final String SYSTEM_API =
            "tioka-modules/tioka-module-system/tioka-module-system-api/src/main/java/com/tioka/system/api/audit/";
    private static final String SYSTEM_BIZ_MAIN =
            "tioka-modules/tioka-module-system/tioka-module-system-biz/src/main/";

    private static final String MIGRATION =
            "tioka-server/src/main/resources/db/migration/V230__m3_obs09_audit_query_keys.sql";
    private static final String MIGRATION_CONTRACT = "docs/contracts/db/V230__m3_obs09_audit_query_keys.sql";
    private static final String ACTIONS = "docs/contracts/audit/actions.yaml";

    private static final String AUDIT_API = SYSTEM_API + "AuditApi.java";
    private static final String AUDIT_EVENT = SYSTEM_API + "AuditEvent.java";
    private static final String CONTROLLER = SYSTEM_BIZ_MAIN + "java/com/tioka/system/api/AuditController.java";
    private static final String SERVICE =
            SYSTEM_BIZ_MAIN + "java/com/tioka/system/application/audit/AuditService.java";
    private static final String REQUEST_CONTEXT =
            SYSTEM_BIZ_MAIN + "java/com/tioka/system/application/audit/AuditRequestContext.java";
    private static final String QUERY_OBJ =
            SYSTEM_BIZ_MAIN + "java/com/tioka/system/infrastructure/persistence/AuditEventQuery.java";
    private static final String WRITE_ROW =
            SYSTEM_BIZ_MAIN + "java/com/tioka/system/infrastructure/persistence/AuditEventWriteRow.java";
    private static final String MAPPER_XML = SYSTEM_BIZ_MAIN + "resources/mapper/AuditEventMapper.xml";
    private static final String PERMISSIONS =
            "tioka-framework/src/main/java/com/tioka/framework/security/Permissions.java";
    private static final String TURN_RUNNER =
            "tioka-modules/tioka-module-runtime/tioka-module-runtime-biz/src/main/java"
                    + "/com/tioka/runtime/kernel/application/TurnRunner.java";

    /** ADR-073 §1 关联键条件（OBS-09 新增的 5 个查询条件）。 */
    private static final String[][] CORRELATION_KEYS = {
            {"trace_id", "traceId"},
            {"request_id", "requestId"},
            {"subject", "subject"},
            {"turn_id", "turnId"},
            {"run_id", "runId"}
    };

    @BeforeAll
    static void resolveRepoRoot() {
        repoRoot = findRepoRoot();
    }

    @Test
    @DisplayName("迁移：V230 双份副本 byte-identical，含运行键列与关联键索引")
    void migrationAddsRunKeysAndIndexes() throws IOException {
        byte[] server = Files.readAllBytes(repoRoot.resolve(MIGRATION));
        byte[] contract = Files.readAllBytes(repoRoot.resolve(MIGRATION_CONTRACT));
        assertArrayEquals(server, contract,
                "迁移须双份同步（byte-identical）：" + MIGRATION + " 与 " + MIGRATION_CONTRACT);

        String sql = new String(server, StandardCharsets.UTF_8);
        assertTrue(sql.contains("ADD COLUMN IF NOT EXISTS turn_id VARCHAR(64)"),
                "audit_event 须补 turn_id 列（OBS-09 查询条件依据）");
        assertTrue(sql.contains("ADD COLUMN IF NOT EXISTS run_id"),
                "audit_event 须补 run_id 列（OBS-09 查询条件依据）");
        assertTrue(sql.contains("CREATE INDEX IF NOT EXISTS idx_audit_request_id ON audit_event (request_id)"),
                "关联键 request_id 须建索引");
        assertTrue(sql.contains("idx_audit_turn_id"), "turn_id 须建索引");
        assertTrue(sql.contains("idx_audit_run_id"), "run_id 须建索引");
        assertFalse(sql.toLowerCase().contains("tenantid"), "全程禁止引入 tenantId（ADR-029）");
    }

    @Test
    @DisplayName("权限点：audit:read:all 与 audit:export 存在并纳入 ALL_CONCRETE")
    void auditPermissionSplitDeclared() throws IOException {
        String src = read(PERMISSIONS);
        assertTrue(src.contains("AUDIT_READ_ALL = \"audit:read:all\""),
                "缺少权限点 audit:read:all（ADR-072 §6）");
        assertTrue(src.contains("AUDIT_EXPORT = \"audit:export\""),
                "缺少权限点 audit:export（ADR-072 §6）");
        int idx = src.indexOf("ALL_CONCRETE");
        assertTrue(idx > 0, "缺少 ALL_CONCRETE 数组");
        String tail = src.substring(idx);
        assertTrue(tail.contains("AUDIT_READ_ALL"), "新权限点 audit:read:all 必须登记进 ALL_CONCRETE");
        assertTrue(tail.contains("AUDIT_EXPORT"), "新权限点 audit:export 必须登记进 ALL_CONCRETE");
    }

    @Test
    @DisplayName("查询契约：AuditQuery 带 5 个关联键 + cursor；AuditCursor 以 <instant>|<id> 编码")
    void auditQueryCarriesCorrelationKeysAndCursor() throws IOException {
        String src = read(AUDIT_API);
        for (String[] key : CORRELATION_KEYS) {
            assertTrue(src.contains(key[1]), "AuditQuery 须带关联键组件 " + key[1]);
        }
        assertTrue(src.contains("String cursor, int limit"),
                "AuditQuery 须以 cursor 取代偏移量分页");
        assertTrue(src.contains("record AuditCursor(Instant ts, String id)"),
                "须有分页游标类型 AuditCursor(ts, id)");
        assertTrue(src.contains("public static AuditCursor parse(String cursor)"),
                "游标须可解析（不透明串）");
        assertTrue(src.contains("cursor.indexOf('|')"), "游标编码须为 <instant>|<id>");
    }

    @Test
    @DisplayName("落库与回读：AuditEvent / WriteRow / Query 均带 turnId、runId 与游标位")
    void eventRowAndQueryCarryRunKeys() throws IOException {
        String event = read(AUDIT_EVENT);
        assertTrue(event.contains("String requestId, String turnId, String runId, String clientIp,"),
                "AuditEvent 须在 requestId 后带 turnId / runId");
        assertTrue(event.contains("public Builder turnId(String turnId)"), "AuditEvent.Builder 须有 turnId");
        assertTrue(event.contains("public Builder runId(String runId)"), "AuditEvent.Builder 须有 runId");

        String row = read(WRITE_ROW);
        assertTrue(row.contains("String turnId,") && row.contains("String runId,"),
                "AuditEventWriteRow 须带 turnId / runId（insert 落库）");

        String query = read(QUERY_OBJ);
        for (String[] key : CORRELATION_KEYS) {
            assertTrue(query.contains(key[1]), "AuditEventQuery 须带关联键字段 " + key[1]);
        }
        assertTrue(query.contains("Instant cursorTs,") && query.contains("String cursorId,"),
                "AuditEventQuery 须带 (ts, id) 游标位");
    }

    @Test
    @DisplayName("查询 SQL：5 条关联键条件 + (ts, id) 游标谓词 + 稳定倒序")
    void mapperSqlGuardsCorrelationKeysAndCursor() throws IOException {
        String xml = read(MAPPER_XML);
        for (String[] key : CORRELATION_KEYS) {
            assertTrue(xml.contains(key[0] + " = #{" + key[1] + "}"),
                    "查询 SQL 缺少关联键条件 " + key[0] + " = #{" + key[1] + "}（ADR-073 §1）");
        }
        assertTrue(xml.contains("#{cursorTs}::timestamptz"), "游标须按 ts 定位");
        assertTrue(xml.contains("AND id < #{cursorId}"), "游标须以 id 破平局（(ts, id) 稳定推进）");
        assertTrue(xml.contains("ORDER BY ts DESC, id DESC"), "结果须按 (ts, id) 倒序返回");
        assertTrue(xml.contains("turn_id, run_id"), "insert 须写 turn_id / run_id");
    }

    @Test
    @DisplayName("控制器：查询/导出双写审计，导出另记条件、行数与文件哈希；权限点细分")
    void controllerWritesAuditAndSplitsPermissions() throws IOException {
        String src = read(CONTROLLER);
        assertTrue(src.contains("private static final String ACTION_QUERY = \"audit.query\";"),
                "查询动作名须为 audit.query");
        assertTrue(src.contains("private static final String ACTION_EXPORT = \"audit.export\";"),
                "导出动作名须为 audit.export");
        assertTrue(src.contains("@RequirePermission({Permissions.AUDIT_READ, Permissions.AUDIT_EXPORT})"),
                "导出须同时要求 audit:read 与 audit:export");
        assertTrue(src.contains("writeAudit(ACTION_QUERY, q, rows.size(), null)"),
                "查询须写审计（不含文件哈希）");
        assertTrue(src.contains("writeAudit(ACTION_EXPORT, q, rows.size(), sha256Hex(bytes))"),
                "导出须写审计并记录文件哈希");
        assertTrue(src.contains("details.put(\"conditions\", conditions(q))"),
                "导出审计须记录查询条件");
        assertTrue(src.contains("details.put(\"rowCount\", rowCount)"), "导出审计须记录行数");
        assertTrue(src.contains("details.put(\"fileHash\", fileHash)"), "导出审计须记录文件哈希");
        assertTrue(src.contains("AuditDegradedSink degraded") && src.contains("degraded.append("),
                "审计自写失败须降级留痕（AuditDegradedSink）");
        assertTrue(src.contains("HEADER_NEXT_CURSOR = \"X-Next-Cursor\""),
                "分页续查游标须经响应头 X-Next-Cursor 返回");
        assertTrue(src.contains("Permissions.AUDIT_READ_ALL"),
                "跨空间查询须以 audit:read:all 判定");
        assertTrue(src.contains(".resourceType(\"audit_event\")"),
                "自写审计的资源类型须为 audit_event");
    }

    @Test
    @DisplayName("服务：游标解析 + turnId/runId 补全（MDC）；关联上下文含 MDC_TURN/MDC_RUN")
    void serviceResolvesCursorAndEnrichesRunKeys() throws IOException {
        String service = read(SERVICE);
        assertTrue(service.contains("AuditApi.AuditCursor.parse(query.cursor())"),
                "服务须解析游标而非偏移量");
        assertTrue(service.contains("AuditRequestContext.currentTurnId()"), "服务须从 MDC 补 turnId");
        assertTrue(service.contains("AuditRequestContext.currentRunId()"), "服务须从 MDC 补 runId");
        assertTrue(service.contains(".turnId(str(r.get(\"turnId\")))"),
                "回读须映射 turnId 列");

        String ctx = read(REQUEST_CONTEXT);
        assertTrue(ctx.contains("MDC_TURN = \"turnId\"") && ctx.contains("MDC_RUN = \"runId\""),
                "关联上下文须声明 MDC_TURN / MDC_RUN");
        assertTrue(ctx.contains("public static String currentTurnId()")
                        && ctx.contains("public static String currentRunId()"),
                "关联上下文须提供 currentTurnId / currentRunId（缺则不生成）");
        assertTrue(ctx.contains("MDC.remove(MDC_TURN)") && ctx.contains("MDC.remove(MDC_RUN)"),
                "clear() 须一并清理 turn / run 键");
    }

    @Test
    @DisplayName("运行侧：TurnRunner 落 turnId 到与审计同名的 MDC key")
    void turnRunnerSetsTurnIdMdc() throws IOException {
        String src = read(TURN_RUNNER);
        assertTrue(src.contains("MDC_TURN_ID = \"turnId\""),
                "TurnRunner 须以与 AuditRequestContext.MDC_TURN 同名的 key 落 MDC");
        assertTrue(src.contains("MDC.put(MDC_TURN_ID, turnId)"), "TurnRunner 须在 Turn 内落 turnId");
        assertTrue(src.contains("MDC.remove(MDC_TURN_ID)"), "Turn 结束须清理 turnId（避免线程复用污染）");
    }

    @Test
    @DisplayName("动作目录：audit.query / audit.export 已登记为 DATA / audit_event")
    void auditSelfActionsRegistered() throws IOException {
        Map<String, Map<?, ?>> catalog = loadCatalog();
        for (String action : new String[]{"audit.query", "audit.export"}) {
            Map<?, ?> entry = catalog.get(action);
            assertTrue(entry != null, "动作目录缺少 " + action + "（ADR-072 §2 登记制）");
            assertEquals("DATA", entry.get("category"), action + " 分类须为 DATA");
            assertEquals("audit_event", entry.get("resourceType"), action + " 资源类型须为 audit_event");
            assertEquals("tioka-module-system", entry.get("module"), action + " 负责模块须为 tioka-module-system");
        }
    }

    /* ---------- 工具 ---------- */

    @SuppressWarnings("unchecked")
    private static Map<String, Map<?, ?>> loadCatalog() throws IOException {
        Path yaml = repoRoot.resolve(ACTIONS);
        assertTrue(Files.isRegularFile(yaml), "缺少动作目录：" + ACTIONS);
        Map<String, Object> root = new Yaml().load(Files.readString(yaml, StandardCharsets.UTF_8));
        Map<String, Map<?, ?>> catalog = new HashMap<>();
        for (Object o : (List<Object>) root.get("actions")) {
            Map<?, ?> e = (Map<?, ?>) o;
            catalog.put(String.valueOf(e.get("action")), e);
        }
        return catalog;
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
