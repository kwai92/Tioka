package com.tioka.system.application.obs;

import com.tioka.system.api.obs.ExtInteractionRecorder;
import com.tioka.system.application.audit.AuditRequestContext;
import com.tioka.system.infrastructure.ExtInteractionRepository;
import com.tioka.system.infrastructure.persistence.ExtInteractionWriteRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * OBS-04a：台账落库行组装离线单测——关联键提取与 MDC 兜底、保留类别、overflow、失败降级。
 */
class DefaultExtInteractionRecorderTest {

    private ExtInteractionRepository repository;
    private DefaultExtInteractionRecorder recorder;

    @BeforeEach
    void setUp() {
        repository = mock(ExtInteractionRepository.class);
        recorder = new DefaultExtInteractionRecorder(repository);
    }

    @AfterEach
    void cleanMdc() {
        AuditRequestContext.clear();
    }

    private static ExtInteractionRecorder.ExtInteractionRecord record(
            Map<String, String> attrs, String externalRef, String subject) {
        Map<String, String> merged = new java.util.HashMap<>(attrs);
        if (subject != null) {
            merged.put("subject", subject);
        }
        return new ExtInteractionRecorder.ExtInteractionRecord(
                ExtInteractionRecorder.Direction.OUT, "http", "open.feishu.cn",
                "open.feishu.cn/im/v1/messages", "POST",
                ExtInteractionRecorder.Status.OK, 200, externalRef, null, 42L, merged);
    }

    private ExtInteractionWriteRow capturedRow() {
        ArgumentCaptor<ExtInteractionWriteRow> captor =
                ArgumentCaptor.forClass(ExtInteractionWriteRow.class);
        verify(repository).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    void mapsFixedLedgerKeysAndComputesRetentionClass() {
        recorder.record(record(Map.of(
                "auth_mode", "SERVICE", "actor_id", "u1", "turn_id", "turn_1",
                "request_digest", "aa", "response_size", "256", "attempt", "2"
        ), "om_123", "contract-9"));

        ExtInteractionWriteRow row = capturedRow();
        assertEquals("OUT", row.direction());
        assertEquals("http", row.kind());
        assertEquals("open.feishu.cn", row.counterparty());
        assertEquals("om_123", row.externalRef());
        assertEquals("SERVICE", row.authMode());
        assertEquals("u1", row.actorId());
        assertEquals("turn_1", row.turnId());
        assertEquals("aa", row.requestDigest());
        assertEquals(256L, row.responseSize());
        assertEquals(2, row.attempt());
        assertEquals("OK", row.status());
        assertEquals(200, row.httpStatus());
        assertEquals(42L, row.latencyMs());
        // 带 subject → BIZ（ADR-074 §1）
        assertEquals("BIZ", row.retentionClass());
    }

    @Test
    void withoutSubjectRetentionClassIsSec() {
        recorder.record(record(Map.of(), null, null));
        assertEquals("SEC", capturedRow().retentionClass());
    }

    @Test
    void fallsBackToMdcForTraceAndRequestIds() {
        AuditRequestContext.currentTraceId(); // 触发生成
        AuditRequestContext.currentRequestId();
        recorder.record(record(Map.of(), null, null));

        ExtInteractionWriteRow row = capturedRow();
        assertEquals(AuditRequestContext.currentTraceId(), row.traceId());
        assertEquals(AuditRequestContext.currentRequestId(), row.requestId());
        assertNull(row.subject());
    }

    @Test
    void attrsOverrideWinsOverMdc() {
        recorder.record(record(Map.of("trace_id", "trc_explicit", "request_id", "req_explicit"), null, null));
        ExtInteractionWriteRow row = capturedRow();
        assertEquals("trc_explicit", row.traceId());
        assertEquals("req_explicit", row.requestId());
    }

    @Test
    void unknownAttrsGoToOverflowJsonAndKnownKeysDoNot() {
        recorder.record(record(Map.of("request_id", "r1", "custom_key", "custom_value"), null, null));
        ExtInteractionWriteRow row = capturedRow();
        String overflow = row.attrsJson();
        assertEquals(null, overflow == null ? null : (overflow.contains("request_id") ? "leak" : null),
                "固定列键不应进 overflow");
        assertEquals(true, overflow != null && overflow.contains("custom_key"),
                "未知键应进 attrs_json");
    }

    @Test
    void repositoryFailureIsSwallowed() {
        doThrow(new RuntimeException("db down")).when(repository).insert(any(ExtInteractionWriteRow.class));
        recorder.record(record(Map.of(), null, null)); // 不应抛出
    }

    @Test
    void multipleRecordsAreAllPersisted() {
        doAnswer(inv -> null).when(repository).insert(any(ExtInteractionWriteRow.class));
        recorder.record(record(Map.of(), "ref-1", null));
        recorder.record(record(Map.of(), "ref-2", null));
        ArgumentCaptor<ExtInteractionWriteRow> captor =
                ArgumentCaptor.forClass(ExtInteractionWriteRow.class);
        verify(repository, org.mockito.Mockito.times(2)).insert(captor.capture());
        List<ExtInteractionWriteRow> rows = captor.getAllValues();
        assertEquals("ref-1", rows.get(0).externalRef());
        assertEquals("ref-2", rows.get(1).externalRef());
    }
}
