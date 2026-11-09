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
 * M2 批次 B-4：代用户令牌保管源码守护（离线、纯文件断言，做法同
 * {@code M2Obs04aLedgerContractTest} 的 nio 读文件）。
 *
 * <p>守护 FND-08 实现不被静默回退：V141 表结构（契约
 * {@code docs/contracts/db/V141__m2_user_credential.sql}）→ 三层封装（DO / Mapper / Repository）
 * → DefaultUserTokenVault（刷新窗口 / 失败降级）→ 回调控制器。真实 provider 联测归 CI IT。
 */
class M2B4VaultContractTest {

    private static Path repoRoot;

    private static final String DO =
            "tioka-modules/tioka-module-connector/tioka-module-connector-biz/src/main/java"
                    + "/com/tioka/connector/infrastructure/persistence/UserCredentialDO.java";
    private static final String MAPPER =
            "tioka-modules/tioka-module-connector/tioka-module-connector-biz/src/main/java"
                    + "/com/tioka/connector/infrastructure/mapper/UserCredentialMapper.java";
    private static final String REPOSITORY =
            "tioka-modules/tioka-module-connector/tioka-module-connector-biz/src/main/java"
                    + "/com/tioka/connector/infrastructure/UserCredentialRepository.java";
    private static final String VAULT =
            "tioka-modules/tioka-module-connector/tioka-module-connector-biz/src/main/java"
                    + "/com/tioka/connector/application/oauth/DefaultUserTokenVault.java";
    private static final String CONTROLLER =
            "tioka-modules/tioka-module-connector/tioka-module-connector-biz/src/main/java"
                    + "/com/tioka/connector/api/OAuthCallbackController.java";
    private static final String API_INTERFACE =
            "tioka-modules/tioka-module-connector/tioka-module-connector-api/src/main/java"
                    + "/com/tioka/connector/api/oauth/UserTokenVault.java";
    private static final String STATE_CODEC =
            "tioka-modules/tioka-module-connector/tioka-module-connector-biz/src/main/java"
                    + "/com/tioka/connector/application/oauth/OAuthStateCodec.java";

    /** application 层（含 oauth 子包）的源文件，不得出现 Mapper import。 */
    private static final List<String> APPLICATION_LAYER_FILES = List.of(VAULT, STATE_CODEC);

    @BeforeAll
    static void resolveRepoRoot() {
        repoRoot = findRepoRoot();
    }

    @Test
    @DisplayName("V141 迁移：sys_user_credential 契约列 + status CHECK + (user_id,provider) 唯一")
    void v141MigrationDefinesCredentialTable() throws IOException {
        String v141 = read("tioka-server/src/main/resources/db/migration/V141__m2_user_credential.sql");
        assertTrue(v141.contains("CREATE TABLE sys_user_credential"), "V141 必须建 sys_user_credential 表");
        for (String col : new String[]{"user_id", "provider", "access_token_enc", "refresh_token_enc",
                "access_expires_at", "refresh_expires_at", "scopes", "key_version", "status",
                "create_at", "update_at"}) {
            assertTrue(v141.contains(col), "sys_user_credential 缺少契约列：" + col);
        }
        assertTrue(v141.contains("CHECK (status IN ('ACTIVE','REVOKED','REFRESH_FAILED'))"),
                "status 状态机约束必须落库");
        assertTrue(v141.contains("UNIQUE (user_id, provider)"),
                "(user_id, provider) 唯一约束必须落库");
        assertFalse(v141.contains("workspace_id"), "契约表无 workspace_id 列，不得越权加列");
        assertFalse(v141.toLowerCase().contains("tenant"), "全程禁止引入 tenantId（ADR-029）");
    }

    @Test
    @DisplayName("三层封装齐备：DO @TableName + Mapper 仅继承 BaseMapper + Repository 封装")
    void persistenceTripleLayerPresent() throws IOException {
        String doSrc = read(DO);
        assertTrue(doSrc.contains("@TableName(\"sys_user_credential\")"), "DO 须绑定 sys_user_credential");
        assertTrue(doSrc.contains("TechnicalRecordDO"), "契约无 create_by/update_by/deleted 列，须继承 TechnicalRecordDO");
        assertFalse(doSrc.contains("tenantId"), "DO 禁止 tenantId");

        String mapperSrc = read(MAPPER);
        assertTrue(mapperSrc.contains("extends BaseMapper<UserCredentialDO>"), "Mapper 仅继承 BaseMapper（无注解 SQL）");

        String repoSrc = read(REPOSITORY);
        assertTrue(repoSrc.contains("@Repository"), "Repository 封装须注册为 @Repository");
        assertTrue(repoSrc.contains("findByUserAndProvider"), "须提供 (user_id, provider) 唯一键查询");
    }

    @Test
    @DisplayName("DefaultUserTokenVault：5 分钟刷新窗口 + 失败降级 REFRESH_FAILED + revoke 保留行")
    void vaultImplementsRefreshAndDegrade() throws IOException {
        String src = read(VAULT);
        assertTrue(src.contains("implements UserTokenVault"), "实现 connector-api 的 UserTokenVault");
        assertTrue(src.contains("REFRESH_AHEAD_SECONDS = 5 * 60"), "剩余不足 5 分钟自动刷新（接口契约）");
        assertTrue(src.contains("REFRESH_FAILED"), "刷新失败须置 REFRESH_FAILED");
        assertTrue(src.contains("\"REVOKED\""), "revoke 置 REVOKED 保留行（不物理删除）");
        assertTrue(src.contains("cipher.encrypt") && src.contains("cipher.decrypt"),
                "令牌须经 CredentialCipher 加解密（明文不落库）");
        assertTrue(src.contains("currentKeyVersion()"), "key_version 按当前 cipher 写入");
        assertTrue(src.contains("Ids.newId(\"cred\")"), "主键用 Ids.newId（ENG-05）");
        assertFalse(src.contains("UUID.randomUUID"), "业务 ID 禁 UUID.randomUUID");
        assertFalse(src.toLowerCase().contains("tenant"), "禁止 tenantId");
    }

    @Test
    @DisplayName("application 层禁直持 Mapper import（经 Repository 封装）")
    void applicationLayerMustNotImportMapper() throws IOException {
        for (String file : APPLICATION_LAYER_FILES) {
            String src = read(file);
            assertFalse(src.contains("infrastructure.mapper"), file + " 不得直持 Mapper（经 Repository 封装）");
        }
    }

    @Test
    @DisplayName("回调控制器：state 验签 → 换 token → 加密落库 → 302 站内 returnTo")
    void callbackControllerWiresVault() throws IOException {
        String src = read(CONTROLLER);
        assertTrue(src.contains("/api/connector/oauth/callback"), "回调端点路径");
        assertTrue(src.contains("@PublicApi"), "回调来自 provider 浏览器重定向，无会话头，须显式 @PublicApi");
        assertTrue(src.contains("stateCodec.decode(state)"), "须验 state 签名（防 CSRF / 篡改）");
        assertTrue(src.contains("startsWith(\"/\")"), "returnTo 须限定站内相对路径（防开放重定向）");
        assertTrue(src.contains("vault.store("), "换 token 后经 vault 加密落库");
        String api = read(API_INTERFACE);
        assertTrue(api.contains("void store("), "connector-api 接口须有 store 写入方法（回调依赖）");
    }

    @Test
    @DisplayName("state 签名：HMAC 派生自既有主密钥，篡改不可通过")
    void stateCodecSignsWithDerivedKey() throws IOException {
        String src = read(STATE_CODEC);
        assertTrue(src.contains("HmacSHA256"), "state 用 HMAC-SHA256 签名");
        assertTrue(src.contains("tioka.credential.master-key"), "签名密钥派生自既有主密钥（与 CredentialCipher 同源）");
        assertTrue(src.contains("MessageDigest.isEqual"), "验签须常数时间比较");
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
