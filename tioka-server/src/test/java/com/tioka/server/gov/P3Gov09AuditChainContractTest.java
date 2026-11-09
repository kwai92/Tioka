package com.tioka.server.gov;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3-GOV-09：审计哈希链封印源码守护（离线、纯文件断言，不启动 Spring；做法同
 * {@code P2L6TraceContinuityContractTest} 的 nio 读文件）。
 *
 * <p>守护 GOV-09（ADR-025 §审计防篡改与外送）的实现不被静默回退：
 * V219 封印结构 → 封印服务（算法口径 / 多实例锁 / 每链事务）→ Mapper 封印 SQL
 * → 校验端点（权限与租户隔离）→ 物理清理守卫（链落地前不得打开）。
 * 端到端语义（真实 PG 链推演）由 CI 的 IT 承接。
 */
class P3Gov09AuditChainContractTest {

    private static Path repoRoot;

    private static final String CHAIN_SERVICE =
            "tioka-modules/tioka-module-system/tioka-module-system-biz/src/main/java"
                    + "/com/tioka/system/application/audit/AuditChainService.java";
    private static final String CHAIN_CONTROLLER =
            "tioka-modules/tioka-module-system/tioka-module-system-biz/src/main/java"
                    + "/com/tioka/system/api/AuditController.java";
    private static final String CHAIN_MAPPER =
            "tioka-modules/tioka-module-system/tioka-module-system-biz/src/main/java"
                    + "/com/tioka/system/infrastructure/mapper/AuditEventMapper.java";
    private static final String CHAIN_MAPPER_XML =
            "tioka-modules/tioka-module-system/tioka-module-system-biz/src/main/resources"
                    + "/mapper/AuditEventMapper.xml";
    private static final String FORWARDER =
            "tioka-modules/tioka-module-system/tioka-module-system-biz/src/main/java"
                    + "/com/tioka/system/application/audit/AuditForwarder.java";

    @BeforeAll
    static void resolveRepoRoot() {
        repoRoot = findRepoRoot();
    }

    @Test
    @DisplayName("V219 迁移：封印锚表 + 未封印部分索引 + _system 链口径")
    void v219MigrationDefinesSealStructure() throws IOException {
        String v219 = read("tioka-server/src/main/resources/db/migration/V219__p3_audit_chain_seal.sql");
        assertTrue(v219.contains("CREATE TABLE IF NOT EXISTS audit_seal"),
                "V219 必须建 audit_seal 封印锚表");
        assertTrue(v219.contains("idx_audit_unsealed") && v219.contains("WHERE hash IS NULL"),
                "V219 必须为未封印记录建部分索引");
        assertTrue(v219.contains("last_hash") && v219.contains("events_sealed"),
                "封印锚须含链末哈希与累计封印数");
        assertTrue(v219.contains("_system"),
                "V219 须声明 workspace 为空的系统级记录归入 _system 链");
    }

    @Test
    @DisplayName("封印服务：sha256(prev+记录) 口径、每租户一链、多实例锁、每链事务")
    void chainServiceImplementsAdrSealAlgorithm() throws IOException {
        String src = read(CHAIN_SERVICE);
        assertTrue(src.contains("SHA-256"),
                "封印算法必须为 sha256（ADR-025 口径）");
        assertTrue(src.contains("prev + \"\\n\" + canonical") || src.contains("prev + '\\n'"),
                "hash 必须基于 prev_hash 与规范化记录拼接计算");
        assertTrue(src.contains("SYSTEM_CHAIN = \"_system\""),
                "workspace 为空的系统级记录须归入 _system 链");
        assertTrue(src.contains("setIfAbsent") && src.contains("LOCK_TTL"),
                "多实例互斥必须走 Redis SETNX 锁（TTL 短于调度间隔）");
        assertTrue(src.contains("TransactionTemplate") || src.contains("transactionTemplate"),
                "每链封印须在事务内执行（哈希回写与锚 upsert 同生共死，crash-safe）");
        assertTrue(src.contains("upsertSeal"),
                "链末哈希须写入封印锚（可外送 SIEM 作外部锚点）");
        assertTrue(src.contains("@Scheduled(fixedDelay = 60_000)"),
                "封印调度须为每分钟一轮（ADR-025 口径）");
    }

    @Test
    @DisplayName("Mapper：封印链五方法齐备（取未封印/回写/锚 upsert/取链/取锚）")
    void mapperExposesSealMethods() throws IOException {
        String api = read(CHAIN_MAPPER);
        for (String method : new String[]{
                "listUnsealed", "markSealed", "upsertSeal", "listSealedByChain", "getSeal"}) {
            assertTrue(api.contains(method), "AuditEventMapper 缺少封印方法：" + method);
        }
        String xml = read(CHAIN_MAPPER_XML);
        for (String id : new String[]{
                "listUnsealed", "markSealed", "upsertSeal", "listSealedByChain", "getSeal"}) {
            assertTrue(xml.contains("id=\"" + id + "\""), "AuditEventMapper.xml 缺少 SQL：" + id);
        }
        assertTrue(xml.contains("ON CONFLICT (workspace_key) DO UPDATE"),
                "锚 upsert 必须幂等（ON CONFLICT 累计封印数）");
    }

    @Test
    @DisplayName("校验端点：/chain/verify 挂 AUDIT_READ，非管理员强制本 workspace 且禁查 _system")
    void controllerVerifiesChainWithTenantIsolation() throws IOException {
        String src = read(CHAIN_CONTROLLER);
        assertTrue(src.contains("/chain/verify"),
                "AuditController 必须暴露链校验端点");
        assertTrue(src.contains("verifyChain") && src.contains("ChainVerifyResult"),
                "校验端点须返回 ChainVerifyResult");
        assertTrue(src.contains("AuditChainService.SYSTEM_CHAIN.equals(key)"),
                "非管理员校验 _system 系统级链必须被拒绝");
        assertTrue(src.contains("key = p.workspaceId()"),
                "非管理员只能校验本 workspace 的链");
    }

    @Test
    @DisplayName("物理清理守卫仍在：链落地后 purge 必须保持默认关闭")
    void physicalPurgeGuardRemainsIntact() throws IOException {
        String src = read(FORWARDER);
        assertTrue(src.contains("isPhysicalPurgeEnabled"),
                "AuditForwarder.purgeExpired 必须受 physicalPurgeEnabled 守卫（默认关闭），防止哈希链断裂");
        assertTrue(src.contains("GOV-09"),
                "purgeExpired 须保留 GOV-09 前不得打开的注释约束");
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
