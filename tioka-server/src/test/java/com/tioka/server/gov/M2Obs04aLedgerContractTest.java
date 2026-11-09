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
 * P3 批次（M2 残余收口 / OBS-04a）：外部交互台账源码守护（离线、纯文件断言，做法同
 * {@code P2L6TraceContinuityContractTest} 的 nio 读文件）。
 *
 * <p>守护 ADR-074 台账实现不被静默回退：V220 表结构 → 落库 Recorder（关联键 / 保留类别 / 失败降级）
 * → EgressClient 出网接线 → Noop 兜底保留。端到端语义（真实 PG 写入）由 CI 的 IT 承接。
 */
class M2Obs04aLedgerContractTest {

    private static Path repoRoot;

    private static final String RECORDER =
            "tioka-modules/tioka-module-system/tioka-module-system-biz/src/main/java"
                    + "/com/tioka/system/application/obs/DefaultExtInteractionRecorder.java";
    private static final String EGRESS_CLIENT =
            "tioka-modules/tioka-module-system/tioka-module-system-biz/src/main/java"
                    + "/com/tioka/system/application/egress/DefaultEgressClient.java";
    private static final String NOOP =
            "tioka-modules/tioka-module-system/tioka-module-system-api/src/main/java"
                    + "/com/tioka/system/api/obs/NoopExtInteractionRecorder.java";

    @BeforeAll
    static void resolveRepoRoot() {
        repoRoot = findRepoRoot();
    }

    @Test
    @DisplayName("V220 迁移：ext_interaction 全字段表 + external_ref 索引 + 保留类别")
    void v220MigrationDefinesLedgerTable() throws IOException {
        String v220 = read("tioka-server/src/main/resources/db/migration/V220__m2_ext_interaction_ledger.sql");
        assertTrue(v220.contains("CREATE TABLE IF NOT EXISTS ext_interaction"),
                "V220 必须建 ext_interaction 台账表");
        for (String col : new String[]{"direction", "kind", "counterparty", "external_ref",
                "dedupe_key", "request_digest", "response_digest", "status", "attempt",
                "retention_class", "latency_ms"}) {
            assertTrue(v220.contains(col), "ext_interaction 缺少 ADR-074 字段：" + col);
        }
        assertTrue(v220.contains("CHECK (retention_class IN ('BIZ', 'SEC'))"),
                "保留类别约束（带 subject 为 BIZ）必须落库");
        assertTrue(v220.contains("idx_ext_int_external_ref"),
                "external_ref 反查索引必须存在（纠纷回查主路径）");
        assertTrue(v220.contains("prev_hash") && v220.contains("hash"),
                "台账哈希链列必须预留（ADR-074：纳入 ADR-072 哈希链）");
    }

    @Test
    @DisplayName("落库 Recorder：关联键提取 + MDC 兜底 + 保留类别 + 失败降级")
    void recorderMapsAttrsAndDegradesOnFailure() throws IOException {
        String src = read(RECORDER);
        assertTrue(src.contains("AuditRequestContext.currentTraceId()"),
                "trace_id 缺失须从 MDC 兜底（ADR-073 同源）");
        assertTrue(src.contains("currentRequestId()"),
                "request_id 缺失须从 MDC 兜底");
        assertTrue(src.contains("\"SEC\" : \"BIZ\"") || src.contains("'SEC'"),
                "保留类别：带 subject 为 BIZ，其余 SEC");
        assertTrue(src.contains("log.warn") && src.contains("catch (RuntimeException"),
                "台账写入失败只记日志，不得阻断业务主流程");
    }

    @Test
    @DisplayName("EgressClient 接线：OUT http 记录 + 守卫拒绝不写 + 可选注入")
    void egressClientWiresLedger() throws IOException {
        String src = read(EGRESS_CLIENT);
        assertTrue(src.contains("ExtInteractionRecorder") && src.contains("ObjectProvider"),
                "出网客户端须可选注入台账（未装配环境静默）");
        assertTrue(src.contains("Direction.OUT") && src.contains("\"http\""),
                "出网请求须记 OUT http 台账行");
        assertTrue(src.contains("statusOf"),
                "须区分 OK / ERROR / TIMEOUT");
        assertTrue(src.contains("getIfAvailable()"),
                "recorder 缺失时静默跳过，不影响出网");
    }

    @Test
    @DisplayName("Noop 兜底保留：API 包内空实现仍在（测试/降级用）")
    void noopImplementationRemainsAvailable() throws IOException {
        String src = read(NOOP);
        assertTrue(src.contains("implements ExtInteractionRecorder"),
                "NoopExtInteractionRecorder 必须保留（台账未装配环境的兜底）");
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
