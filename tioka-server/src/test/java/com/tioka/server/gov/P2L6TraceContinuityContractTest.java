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
 * P2-L6：traceId 全链贯通源码守护（离线、纯文件断言，不启动 Spring；
 * 做法同 {@code P1L8ArchSuiteIntegrityTest} 的 nio 读文件）。
 *
 * <p>守护四个环节的实现不被静默回退：
 * HTTP 入口 traceparent 抽取 → 审计 enrich（spanId/service）→ MQ 生产侧 MDC 回退
 * → MQ 消费侧 MDC 回写。端到端语义由 {@code P2L6TraceContinuityIT}（@Tag("it")）验证。
 */
class P2L6TraceContinuityContractTest {

    private static Path repoRoot;

    @BeforeAll
    static void resolveRepoRoot() {
        repoRoot = findRepoRoot();
    }

    @Test
    @DisplayName("HTTP 入口 AuditRequestContextFilter 抽取 traceparent")
    void httpFilterParsesTraceparent() throws IOException {
        String src = read("tioka-modules/tioka-module-system/tioka-module-system-biz/src/main/java"
                + "/com/tioka/system/application/audit/AuditRequestContextFilter.java");
        assertTrue(src.contains("\"traceparent\""),
                "AuditRequestContextFilter 必须读取 traceparent 请求头");
        assertTrue(src.contains("TraceparentParser.parse"),
                "AuditRequestContextFilter 必须调用 TraceparentParser 解析");
        assertTrue(src.contains("MDC_SPAN"),
                "合法 traceparent 时须写 MDC spanId");
    }

    @Test
    @DisplayName("审计 enrich 补 spanId / service，落库行携带两列")
    void auditEnrichFillsSpanAndService() throws IOException {
        String service = read("tioka-modules/tioka-module-system/tioka-module-system-biz/src/main/java"
                + "/com/tioka/system/application/audit/AuditService.java");
        assertTrue(service.contains("currentSpanId"),
                "AuditService.enrich 须从 MDC 补 spanId");
        assertTrue(service.contains("tioka.observability.service-name"),
                "service 须取配置 tioka.observability.service-name");

        String row = read("tioka-modules/tioka-module-system/tioka-module-system-biz/src/main/java"
                + "/com/tioka/system/infrastructure/persistence/AuditEventWriteRow.java");
        assertTrue(row.contains("spanId") && row.contains("service"),
                "AuditEventWriteRow 须含 spanId / service");

        String mapper = read("tioka-modules/tioka-module-system/tioka-module-system-biz/src/main/resources"
                + "/mapper/AuditEventMapper.xml");
        assertTrue(mapper.contains("span_id") && mapper.contains("service"),
                "AuditEventMapper.xml insert/SELECT 须含 span_id / service 列");
    }

    @Test
    @DisplayName("迁移 V217 为 audit_event 增加 span_id / service 列")
    void migrationV217AddsSpanAndService() throws IOException {
        Path sql = repoRoot.resolve(
                "tioka-server/src/main/resources/db/migration/V217__p2_audit_span_service.sql");
        assertTrue(Files.isRegularFile(sql), "缺少 V217 迁移：" + sql);
        String text = Files.readString(sql, StandardCharsets.UTF_8);
        assertTrue(text.contains("span_id") && text.contains("service"),
                "V217 须为 audit_event 加 span_id / service 列");
    }

    @Test
    @DisplayName("MQ 消费侧 IdempotentDeliveryService 回写 MDC traceId")
    void consumerWritesMdcTraceId() throws IOException {
        String src = read("tioka-modules/tioka-module-infra/tioka-module-infra-biz/src/main/java"
                + "/com/tioka/infra/messaging/IdempotentDeliveryService.java");
        assertTrue(src.contains("MDC.put(MDC_TRACE_ID") && src.contains("MDC.put(MDC_SPAN_ID"),
                "消费侧派发 handler 前须回写 MDC traceId / spanId");
        assertTrue(src.contains("envelope.traceId()"),
                "Span 无效时须回退 envelope.traceId 写 MDC");
        assertTrue(src.contains("MDC.remove(MDC_TRACE_ID") && src.contains("MDC.remove(MDC_SPAN_ID"),
                "finally 须清理消费线程 MDC");
    }

    @Test
    @DisplayName("MQ 生产侧 OutboxEventPublisher Span 无效时回退 MDC traceId")
    void producerFallsBackToMdcTraceId() throws IOException {
        String src = read("tioka-modules/tioka-module-infra/tioka-module-infra-biz/src/main/java"
                + "/com/tioka/infra/messaging/OutboxEventPublisher.java");
        assertTrue(src.contains("currentMdcTraceId"),
                "Span 无效时 envelope.traceId 须回退 MDC traceId");
        assertTrue(src.contains("MDC.get(IdempotentDeliveryService.MDC_TRACE_ID)"),
                "生产侧 MDC key 须与消费侧同源（traceId）");
    }

    private static String read(String relative) throws IOException {
        Path file = repoRoot.resolve(relative);
        assertTrue(Files.isRegularFile(file), "缺少源文件：" + file);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** 同 P1L8ArchSuiteIntegrityTest：从 user.dir 向上找仓库根。 */
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
}
