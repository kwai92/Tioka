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
 * M4 / OBS-12：证据包导出（主体 / 请求范围）的源码守护（离线、纯文件断言，做法同
 * {@link M3Obs09AuditQueryContractTest} / {@link AuditedActionCatalogTest}）。
 *
 * <p>守护 ADR-075 §5 / M4-06 §7：对 {@code subject} 或 {@code request_id} 装配七段
 * （时间线、相关审计、外部交互、运行清单、审批、配置版本、封印校验）；打包给文件清单 + 各文件哈希 +
 * 包整体哈希 + 平台密钥签名；导出受 {@code audit:export}（+ {@code audit:read}）控制，CONFIDENTIAL
 * 按导出人权限决定是否包含；导出写审计 {@code timeline.evidence.export}（ADR-072 §6）。
 * 端到端语义（真实 PG 导出、公钥独立校验、改动任一文件后失败）由 CI 的 IT 承接
 * （{@code EvidencePackVerifyIT} / {@code EvidencePackLabelIT}）。
 */
class M4Obs12EvidencePackContractTest {

    private static Path repoRoot;

    private static final String SYSTEM_API_MAIN =
            "tioka-modules/tioka-module-system/tioka-module-system-api/src/main/java/com/tioka/system/api/";
    private static final String SYSTEM_BIZ_MAIN =
            "tioka-modules/tioka-module-system/tioka-module-system-biz/src/main/";

    private static final String MIGRATION =
            "tioka-server/src/main/resources/db/migration/V261__m4_obs12_evidence_pack.sql";
    private static final String MIGRATION_CONTRACT = "docs/contracts/db/V261__m4_obs12_evidence_pack.sql";
    private static final String ACTIONS = "docs/contracts/audit/actions.yaml";
    private static final String AUDITED_CATALOG_TEST =
            "tioka-server/src/test/java/com/tioka/server/gov/AuditedActionCatalogTest.java";
    private static final String PERMISSIONS =
            "tioka-framework/src/main/java/com/tioka/framework/security/Permissions.java";
    private static final String PERSISTENCE_PROPS =
            "tioka-framework/src/main/java/com/tioka/framework/persistence/PersistenceProperties.java";

    private static final String SIGNER_PORT = SYSTEM_API_MAIN + "evidence/EvidencePackSigner.java";
    private static final String SIGNER_IMPL = SYSTEM_BIZ_MAIN
            + "java/com/tioka/system/infrastructure/spi/EvidencePackSignerImpl.java";
    private static final String VERIFIER = SYSTEM_BIZ_MAIN
            + "java/com/tioka/system/application/evidence/EvidencePackVerifier.java";
    private static final String SERVICE = SYSTEM_BIZ_MAIN
            + "java/com/tioka/system/application/evidence/EvidencePackService.java";
    private static final String CONTROLLER = SYSTEM_BIZ_MAIN
            + "java/com/tioka/system/api/EvidencePackController.java";
    private static final String MAPPER = SYSTEM_BIZ_MAIN
            + "java/com/tioka/system/infrastructure/mapper/EvidencePackMapper.java";
    private static final String MAPPER_XML = SYSTEM_BIZ_MAIN + "resources/mapper/EvidencePackMapper.xml";

    /** 证据包七段（ADR-075 §5）：段常量名 → 段键。 */
    private static final String[][] SECTIONS = {
            {"SECTION_TIMELINE", "timeline"},
            {"SECTION_AUDIT", "audit"},
            {"SECTION_EXT", "ext-interaction"},
            {"SECTION_MANIFEST", "run-manifest"},
            {"SECTION_APPROVALS", "approvals"},
            {"SECTION_CONFIG", "config-versions"},
            {"SECTION_SEAL", "audit-seal"}
    };

    @BeforeAll
    static void resolveRepoRoot() {
        repoRoot = findRepoRoot();
    }

    @Test
    @DisplayName("迁移：V261 双份副本 byte-identical，含范围 / 包哈希 / 签名列，且不进入 scope-exempt")
    void migrationAddsEvidencePackTable() throws IOException {
        byte[] server = Files.readAllBytes(repoRoot.resolve(MIGRATION));
        byte[] contract = Files.readAllBytes(repoRoot.resolve(MIGRATION_CONTRACT));
        assertArrayEquals(server, contract,
                "迁移须双份同步（byte-identical）：" + MIGRATION + " 与 " + MIGRATION_CONTRACT);

        String sql = new String(server, StandardCharsets.UTF_8);
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS evidence_pack"),
                "须建 evidence_pack 登记表（ADR-075 §5）");
        assertTrue(sql.contains("scope_type VARCHAR(16) NOT NULL CHECK (scope_type IN ('SUBJECT', 'REQUEST'))"),
                "范围类型须限 SUBJECT / REQUEST");
        assertTrue(sql.contains("workspace_id VARCHAR(64)"),
                "须有 workspace_id 列（平台级导出可空）");
        assertTrue(sql.contains("package_hash VARCHAR(128)"),
                "须存包整体哈希");
        assertTrue(sql.contains("signature TEXT NOT NULL"),
                "须存平台密钥签名");
        assertTrue(sql.contains("signature_alg VARCHAR(32) NOT NULL DEFAULT 'SHA256withRSA'"),
                "签名算法默认 SHA256withRSA");
        assertTrue(sql.contains("idx_evidence_pack_scope") && sql.contains("idx_evidence_pack_workspace"),
                "范围与空间检索须建索引");
        assertFalse(sql.toLowerCase().contains("tenantid"), "全程禁止引入 tenantId（ADR-029）");

        // 有 workspace_id 的表禁止进入 workspace-scope-exempt-tables（PersistenceArchTest 硬约束）
        String props = read(PERSISTENCE_PROPS);
        assertFalse(props.contains("\"evidence_pack\""),
                "evidence_pack 有 workspace_id 列，禁止登记进 workspace-scope-exempt-tables");
    }

    @Test
    @DisplayName("权限点：导出须同时要求 audit:read 与 audit:export")
    void exportPermissionsDeclared() throws IOException {
        String src = read(PERMISSIONS);
        assertTrue(src.contains("AUDIT_EXPORT = \"audit:export\""),
                "缺少权限点 audit:export（ADR-075 §5）");
        int idx = src.indexOf("ALL_CONCRETE");
        assertTrue(idx > 0, "缺少 ALL_CONCRETE 数组");
        assertTrue(src.substring(idx).contains("AUDIT_EXPORT"),
                "audit:export 必须登记进 ALL_CONCRETE");

        String controller = read(CONTROLLER);
        assertTrue(controller.contains("@RequirePermission({Permissions.AUDIT_READ, Permissions.AUDIT_EXPORT})"),
                "导出须同时要求 audit:read 与 audit:export（ADR-075 §5）");
        assertTrue(controller.contains("@RequirePermission(Permissions.AUDIT_READ)"),
                "公钥下发须要求 audit:read");
    }

    @Test
    @DisplayName("签名：端口在 api、实现在 SecretStore 白名单包，用 SHA256withRSA 且私钥 at-rest")
    void signerInWhitelistedPackageAndSignsWithRsa() throws IOException {
        String port = read(SIGNER_PORT);
        assertTrue(port.contains("interface EvidencePackSigner"),
                "须有端口 EvidencePackSigner（system-api）");
        assertTrue(port.contains("String sign(byte[]"), "端口须提供 sign(byte[])");
        assertTrue(port.contains("String publicKeyPem()"), "端口须提供 publicKeyPem()（供离线验签）");
        assertTrue(port.contains("String keyId()"), "端口须提供 keyId()（密钥轮换追溯）");
        assertFalse(port.contains("import com.tioka.system.api.spi.SecretStore;"),
                "端口不得 import SecretStore（密钥仅实现侧接触）");

        String impl = read(SIGNER_IMPL);
        assertTrue(impl.contains("package com.tioka.system.infrastructure.spi;"),
                "实现须落在 SecretStore 白名单包 com.tioka.system.infrastructure.spi（ADR-014 D5）");
        assertTrue(impl.contains("\"SHA256withRSA\""), "签名算法须为 SHA256withRSA");
        assertTrue(impl.contains("secrets.get(") && impl.contains("secrets.put("),
                "私钥须 at-rest 存 SecretStore（首用生成后持久化）");
        assertTrue(impl.contains("synchronized KeyPair keyPair()"),
                "首用生成须互斥（基础设施层，不受应用层禁 synchronized 约束）");
    }

    @Test
    @DisplayName("服务：七段装配 + 包哈希 + 平台签名 + 审计 timeline.evidence.export + 降级留痕")
    void serviceAssemblesSevenSectionsAndSigns() throws IOException {
        String src = read(SERVICE);
        for (String[] section : SECTIONS) {
            assertTrue(src.contains(section[0] + " = \"" + section[1] + "\""),
                    "七段键须齐备，缺少段常量 " + section[0] + " = \"" + section[1] + "\"（ADR-075 §5）");
            assertTrue(src.contains("sections.put(" + section[0]),
                    "段 " + section[0] + " 须纳入打包");
        }
        assertTrue(src.contains("static final String ACTION_EXPORT = \"timeline.evidence.export\";"),
                "导出动作名须为 timeline.evidence.export（ADR-072 §6）");
        assertTrue(src.contains(".action(ACTION_EXPORT)"), "审计须以该动作名写入");
        assertTrue(src.contains(".resourceType(\"evidence_pack\")"),
                "审计资源类型须为 evidence_pack");
        assertTrue(src.contains("details.put(\"packageHash\", packageHash)"),
                "审计须记录包整体哈希");
        assertTrue(src.contains("details.put(\"rowCount\", rowCount)")
                        && src.contains("details.put(\"fileCount\", fileCount)"),
                "审计须记录行数与文件数");
        assertTrue(src.contains("details.put(\"includeConfidential\", includeConfidential)"),
                "审计须记录是否含 CONFIDENTIAL");
        assertTrue(src.contains("EvidencePackVerifier.packageHash("),
                "包整体哈希口径须复用于独立重算");
        assertTrue(src.contains("signer.sign(packageHash.getBytes("),
                "须以平台密钥对包整体哈希签名");
        assertTrue(src.contains("clearance.ordinal() >= DataLabel.CONFIDENTIAL.ordinal()"),
                "CONFIDENTIAL 是否包含须按导出人 clearance 判定（与时间线查看同口径）");
        assertTrue(src.contains("Permissions.TRAJECTORY_READ_CONTENT"),
                "持 trajectory:read:content 者上限 SECRET");
        assertTrue(src.contains("degraded.append("),
                "审计自写失败须降级留痕（AuditDegradedSink）");
    }

    @Test
    @DisplayName("校验：离线重算各文件哈希 / 包哈希并以 SHA256withRSA 验签")
    void verifierRecomputesHashesAndVerifies() throws IOException {
        String src = read(VERIFIER);
        assertTrue(src.contains("record VerifyReport("),
                "须提供可读校验报告 VerifyReport");
        assertTrue(src.contains("sha256Hex(content"), "须重算各文件内容哈希");
        assertTrue(src.contains("packageHash(declared)"), "须重算包整体哈希");
        assertTrue(src.contains("Signature.getInstance(\"SHA256withRSA\")"), "须以 SHA256withRSA 验签");
        assertTrue(src.contains("verifier.verify("), "须做签名验证并返回布尔结果");
        assertFalse(src.contains("new ObjectMapper()"),
                "禁止 new ObjectMapper（ENG-05），须复用宿主 Bean");
    }

    @Test
    @DisplayName("控制器与持久化：附件下载 + 包头；Mapper 旁路 ScopeLine 且 XML 显式过滤 deleted")
    void controllerAndPersistenceContract() throws IOException {
        String controller = read(CONTROLLER);
        assertTrue(controller.contains("@RequestMapping(\"/api/system/evidence-pack\")"),
                "控制器路径须为 /api/system/evidence-pack");
        assertTrue(controller.contains("HEADER_PACK_ID = \"X-Pack-Id\""),
                "响应头须回包标识 X-Pack-Id");
        assertTrue(controller.contains("HEADER_PACK_SHA256 = \"X-Pack-Sha256\""),
                "响应头须回包哈希 X-Pack-Sha256");
        assertTrue(controller.contains("attachment; filename="),
                "导出须以附件下载返回");
        assertTrue(controller.contains("@GetMapping(\"/public-key\")"),
                "须提供平台公钥接口供离线验签");

        String mapper = read(MAPPER);
        assertTrue(mapper.contains("@InterceptorIgnore(tenantLine = \"true\")"),
                "平台级导出须旁路 ScopeLine（空间隔离在服务层显式校验）");
        String xml = read(MAPPER_XML);
        assertTrue(xml.contains("deleted = false"),
                "旁路 ScopeLine 后 XML 须显式过滤 deleted = false");
        assertTrue(xml.contains("FROM evidence_pack"), "查询须指向 evidence_pack 表");
    }

    @Test
    @DisplayName("动作目录：timeline.evidence.export 已登记为 DATA / evidence_pack / tioka-module-system")
    void exportActionRegistered() throws IOException {
        Map<String, Map<?, ?>> catalog = loadCatalog();
        Map<?, ?> entry = catalog.get("timeline.evidence.export");
        assertTrue(entry != null, "动作目录缺少 timeline.evidence.export（ADR-072 §2 登记制）");
        assertEquals("DATA", entry.get("category"), "分类须为 DATA");
        assertEquals("evidence_pack", entry.get("resourceType"), "资源类型须为 evidence_pack");
        assertEquals("tioka-module-system", entry.get("module"), "负责模块须为 tioka-module-system");

        String auditCatalogTest = read(AUDITED_CATALOG_TEST);
        assertTrue(auditCatalogTest.contains("\"timeline.evidence.export\""),
                "AuditedActionCatalogTest.DIRECT_ACTIONS 须登记该固定动作名（源码内直写点）");
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
