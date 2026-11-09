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
 * <p>spanId / service 组件与 span_id / service 列已随 P2-L6（V217 迁移）落地，
 * 原缺口固化断言已反转；OTel agent 挂载等仍开放的缺口见
 * {@code docs/architecture/design-notes/P1-L6-gap-ledger.md}。
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
     * P2-L6 已落地：AuditEvent 含 spanId / service 组件（V217 迁移加列）。
     * 原 P1 缺口固化断言（不含 spanId / service）已随实现反转。
     */
    @Test
    @DisplayName("AuditEvent 含 spanId / service 组件（P2-L6 已落地）")
    void auditEventCarriesSpanAndService() throws Exception {
        Class<?> event = Class.forName(AUDIT_EVENT);
        List<String> comps = recordComponentNames(event);

        assertTrue(comps.contains("spanId"),
                "P2-L6 落地后 AuditEvent 必须含 spanId（audit_event.span_id，V217）");
        assertTrue(comps.contains("service"),
                "P2-L6 落地后 AuditEvent 必须含 service（audit_event.service，V217）");
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
