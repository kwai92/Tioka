package com.tioka.server.gov;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 / L6（审计字段语义与 traceId 贯通）离线契约测试：纯 JUnit5 + 反射，
 * 不启 Spring、不依赖 Redis / PostgreSQL（离线验收）。
 *
 * <p>固化「代码现状与 L6 验收准则一致」的部分；span_id / service 列、三套 trace
 * 互通、OTel agent 部署等缺口见 {@code docs/architecture/design-notes/P1-L6-gap-ledger.md}，归 P2。
 */
class P1L6AuditFieldContractTest {

    private static final String AUDIT_EVENT = "com.tioka.system.api.audit.AuditEvent";
    private static final String AUDIT_REQUEST_CONTEXT = "com.tioka.system.application.audit.AuditRequestContext";

    private static List<String> recordComponentNames(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName).toList();
    }

    @Test
    @DisplayName("AuditEvent 携带 workspaceId / traceId / requestId")
    void auditEventCarriesWorkspaceAndTrace() throws Exception {
        Class<?> event = Class.forName(AUDIT_EVENT);
        List<String> comps = recordComponentNames(event);

        assertTrue(comps.contains("workspaceId"), "AuditEvent 必须含 workspaceId");
        assertTrue(comps.contains("traceId"), "AuditEvent 必须含 traceId");
        assertTrue(comps.contains("requestId"), "AuditEvent 必须含 requestId（OBS-01 / ADR-072）");
        assertTrue(comps.contains("clientIp"));
        assertTrue(comps.contains("details"));
    }

    @Test
    @DisplayName("AuditEvent 无 tenantId（ADR-029 修订；47-GL）")
    void auditEventHasNoTenantId() throws Exception {
        Class<?> event = Class.forName(AUDIT_EVENT);
        assertFalse(recordComponentNames(event).contains("tenantId"),
                "AuditEvent 不得引入 tenantId（权威隔离字段为 workspaceId）");
    }

    /**
     * 缺口固化断言：AuditEvent 当前不含 spanId / service 组件。
     * P2 落地 span_id / service 列（需 Flyway 迁移）后，本断言须随实现更新
     * （改为断言存在 spanId / service）。
     */
    @Test
    @DisplayName("AuditEvent 暂无 spanId / service 组件（P2 已知缺口，落地后本断言需反转）")
    void auditEventSpanServiceAreKnownGaps() throws Exception {
        Class<?> event = Class.forName(AUDIT_EVENT);
        List<String> comps = recordComponentNames(event);

        assertFalse(comps.contains("spanId"),
                "当前代码无 spanId —— 若本断言失败说明 P2 缺口已落地，请更新此测试与 P1-L6-gap-ledger.md");
        assertFalse(comps.contains("service"),
                "当前代码无 service —— 若本断言失败说明 P2 缺口已落地，请更新此测试与 P1-L6-gap-ledger.md");
    }

    @Test
    @DisplayName("审计 MDC trace key 常量为 \"traceId\"（AuditRequestContext）")
    void auditMdcTraceKeyIsTraceId() throws Exception {
        Class<?> ctx = Class.forName(AUDIT_REQUEST_CONTEXT);
        Object mdcTrace = ctx.getDeclaredField("MDC_TRACE").get(null);
        assertEquals("traceId", mdcTrace);
        Object mdcRequest = ctx.getDeclaredField("MDC_REQUEST").get(null);
        assertEquals("requestId", mdcRequest);
    }
}
