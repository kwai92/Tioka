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
 * SCH-09（ADR-066 §7.4；V233）：结构化输出人工补录任务表迁移源码契约守护
 * （离线、纯文件断言，做法同 {@link M3C7Sch07MigrationContractTest}）。
 *
 * <p>{@code runtime_human_task} 承载 {@code data.complete} 任务：自动链路失败降级到 HUMAN 步骤时
 * 生成，人提交经第 3〜8 步校验后续跑原 Turn；本表带 {@code workspace_id}，ScopeLine
 * deny-by-default 下按空间过滤，**不进** exempt 名单。
 */
class M3C9Sch09MigrationContractTest {

    private static Path repoRoot;

    private static final String V233_SQL =
            "tioka-server/src/main/resources/db/migration/V233__m3_sch09_human_task.sql";
    private static final String V233_CONTRACT =
            "docs/contracts/db/V233__m3_sch09_human_task.sql";
    private static final String PERSISTENCE_PROPS =
            "tioka-framework/src/main/java/com/tioka/framework/persistence/PersistenceProperties.java";
    private static final String RUNTIME_BIZ = "tioka-modules/tioka-module-runtime/tioka-module-runtime-biz"
            + "/src/main/java/com/tioka/runtime";
    private static final String RUNTIME_MAPPER = "tioka-modules/tioka-module-runtime/tioka-module-runtime-biz"
            + "/src/main/resources/mapper/RuntimeHumanTaskMapper.xml";

    @BeforeAll
    static void resolveRepoRoot() {
        repoRoot = findRepoRoot();
    }

    @Test
    @DisplayName("V233：建 runtime_human_task 表（kind/schema_ref/pending_pointers/status CHECK）+ 三个索引")
    void v233CreatesHumanTaskTable() throws IOException {
        String sql = read(V233_SQL);
        assertTrue(sql.contains("CREATE TABLE runtime_human_task"), "V233 必须建 runtime_human_task");
        for (String col : new String[]{"kind", "schema_ref", "candidate_json", "pending_pointers",
                "answer_json", "expires_at", "workspace_id"}) {
            assertTrue(sql.contains(col), "V233 缺少列：" + col);
        }
        assertTrue(sql.contains("CHECK (status IN ('PENDING','SUBMITTED','EXPIRED','CANCELLED'))"),
                "状态必须受 CHECK 约束（四态）");
        assertTrue(sql.contains("DEFAULT 'data.complete'"), "kind 缺省 data.complete");
        for (String idx : new String[]{"idx_human_task_subject", "idx_human_task_pending", "idx_human_task_expiry"}) {
            assertTrue(sql.contains(idx), "V233 必须建索引：" + idx);
        }
    }

    @Test
    @DisplayName("契约副本与迁移内容一致（docs/contracts/db）")
    void contractCopyMatchesMigration() throws IOException {
        assertEquals(read(V233_SQL), read(V233_CONTRACT), "V233 契约副本必须与迁移源码逐字一致");
    }

    @Test
    @DisplayName("带 workspace_id 的表不得进入 ScopeLine 旁路白名单")
    void tableIsNotScopeExempt() throws IOException {
        assertFalse(read(PERSISTENCE_PROPS).contains("\"runtime_human_task\""),
                "runtime_human_task 带 workspace_id，须按空间过滤而非登记 exempt");
    }

    @Test
    @DisplayName("写入链路就位：DO 字段 + Mapper insert/select 投影")
    void writeChainWired() throws IOException {
        String doSrc = read(RUNTIME_BIZ + "/infrastructure/persistence/RuntimeHumanTaskDO.java");
        for (String col : new String[]{"schemaRef", "candidateJson", "pendingPointers",
                "answerJson", "expiresAt", "workspaceId"}) {
            assertTrue(doSrc.contains(col), "RuntimeHumanTaskDO 缺少字段：" + col);
        }
        String mapper = read(RUNTIME_MAPPER);
        assertTrue(mapper.contains("schema_ref AS schemaRef"), "Mapper 未投影 schema_ref");
        assertTrue(mapper.contains("pending_pointers AS pendingPointers"), "Mapper 未投影 pending_pointers");
        assertTrue(mapper.contains("answer_json AS answerJson"), "Mapper 未投影 answer_json");
        assertTrue(mapper.contains("INSERT INTO runtime_human_task"), "Mapper 未写 insert");
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
