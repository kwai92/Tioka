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
 * SCH-06（ADR-066 §4；V177）：模型结构化输出能力记录迁移源码契约守护（离线、纯文件断言，
 * 做法同 {@link M3C1aMigrationContractTest}）。
 */
class M3C6Sch06MigrationContractTest {

    private static Path repoRoot;

    private static final String V177_SQL =
            "tioka-server/src/main/resources/db/migration/V177__m3_sch06_structured_capability.sql";
    private static final String V177_CONTRACT =
            "docs/contracts/db/V177__m3_sch06_structured_capability.sql";
    private static final String PERSISTENCE_PROPS =
            "tioka-framework/src/main/java/com/tioka/framework/persistence/PersistenceProperties.java";
    private static final String BIZ = "tioka-modules/tioka-module-model/tioka-module-model-biz/src/main/java"
            + "/com/tioka/model";

    @BeforeAll
    static void resolveRepoRoot() {
        repoRoot = findRepoRoot();
    }

    @Test
    @DisplayName("V177：建能力记录表 model_structured_capability（PK model_ref）")
    void v177CreatesCapabilityTable() throws IOException {
        String sql = read(V177_SQL);
        assertTrue(sql.contains("CREATE TABLE model_structured_capability"),
                "V177 必须建 model_structured_capability");
        for (String col : new String[]{
                "model_ref", "version", "modes", "keywords", "limits", "thinking_compat",
                "multimodal_downgrade", "prompt_keyword", "max_tokens_advice", "stability",
                "refusal_signal", "truncation_signal", "verified_at", "conformance"}) {
            assertTrue(sql.contains(col), "能力记录缺少契约列：" + col);
        }
        assertTrue(sql.contains("PRIMARY KEY"), "PK 必须落在 model_ref");
        assertFalse(sql.toLowerCase().contains("tenantid"), "全程禁止引入 tenantId（ADR-029）");
        // 只看 DDL 体，头部注释会解释「无 workspace_id」故不能整体断言
        String ddl = sql.substring(sql.indexOf("CREATE TABLE"));
        assertFalse(ddl.contains("workspace_id"), "能力记录为模型级全局配置，不得有 workspace_id（须 exempt）");
    }

    @Test
    @DisplayName("契约副本与迁移内容一致（docs/contracts/db）")
    void contractCopyMatchesMigration() throws IOException {
        assertEquals(read(V177_SQL), read(V177_CONTRACT), "V177 契约副本必须与迁移源码逐字一致");
    }

    @Test
    @DisplayName("无 workspace_id 的表必须登记 ScopeLine 旁路白名单")
    void tableRegisteredAsScopeExempt() throws IOException {
        assertTrue(read(PERSISTENCE_PROPS).contains("\"model_structured_capability\""),
                "model_structured_capability 无 workspace_id，须登记 workspace-scope-exempt-tables");
    }

    @Test
    @DisplayName("查询实现与持久化链路就位（DO/Mapper/Repository/QueryApi）")
    void capabilityQueryChainWired() {
        assertTrue(Files.exists(repoRoot.resolve(BIZ
                + "/infrastructure/persistence/ModelStructuredCapabilityDO.java")), "缺少能力记录 DO");
        assertTrue(Files.exists(repoRoot.resolve(BIZ
                + "/infrastructure/mapper/ModelStructuredCapabilityMapper.java")), "缺少能力记录 Mapper");
        assertTrue(Files.exists(repoRoot.resolve(BIZ
                + "/infrastructure/ModelStructuredCapabilityRepository.java")), "缺少能力记录 Repository");
        assertTrue(Files.exists(repoRoot.resolve(BIZ
                + "/application/profile/StructuredCapabilityQueryService.java")), "缺少 StructuredCapabilityQueryApi 实现");
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
