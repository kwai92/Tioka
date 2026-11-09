package com.tioka.system.application.obs;

import com.tioka.framework.id.Ids;
import com.tioka.system.api.obs.ExtInteractionRecorder;
import com.tioka.system.application.audit.AuditRequestContext;
import com.tioka.system.infrastructure.ExtInteractionRepository;
import com.tioka.system.infrastructure.persistence.ExtInteractionWriteRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 外部交互台账落库实现（OBS-04a / ADR-074）。
 *
 * <p>写入约定：
 * <ul>
 *   <li>关联键优先取 {@code attrs}（调用方显式传入），缺失时 trace_id / request_id 从
 *       {@link AuditRequestContext}（MDC）兜底，保证与审计、traceId 贯通（ADR-073）同源。</li>
 *   <li>保留类别（ADR-074 §1）：带 {@code subject} 的行 BIZ，其余 SEC。</li>
 *   <li>超过固定列的 attrs 余量整体落 {@code attrs_json}。</li>
 *   <li>台账失败只记日志不阻断主流程——台账是观测面，不能让业务写入因它失败。</li>
 * </ul>
 */
@Component
public class DefaultExtInteractionRecorder implements ExtInteractionRecorder {
    private static final Logger log = LoggerFactory.getLogger(DefaultExtInteractionRecorder.class);

    /** attrs 中映射到固定列的键（超出部分进 attrs_json）。 */
    static final String[] LEDGER_KEYS = {
            "auth_mode", "credential_id", "actor_id", "run_as",
            "trace_id", "request_id", "subject", "turn_id", "run_id", "change_set_id", "tool_call_id",
            "idempotency_key", "request_digest", "response_digest",
            "request_size", "response_size", "payload_ref", "data_label",
            "error_code", "attempt"
    };

    private final ExtInteractionRepository repository;

    public DefaultExtInteractionRecorder(ExtInteractionRepository repository) {
        this.repository = repository;
    }

    @Override
    public void record(ExtInteractionRecord record) {
        try {
            repository.insert(toRow(record));
        } catch (RuntimeException e) {
            log.warn("ext interaction ledger write failed kind={} operation={}: {}",
                    record.kind(), record.operation(), e.toString());
        }
    }

    ExtInteractionWriteRow toRow(ExtInteractionRecord record) {
        Instant now = Instant.now();
        String subject = attr(record, "subject");
        String traceId = orElse(attr(record, "trace_id"), AuditRequestContext.currentTraceId());
        String requestId = orElse(attr(record, "request_id"), AuditRequestContext.currentRequestId());
        String errorCode = attr(record, "error_code");
        Integer attempt = intAttr(record, "attempt", 1);
        // 剩余 attrs（非固定列、非已提取列）进 attrs_json，保持插入序
        Map<String, String> overflow = new LinkedHashMap<>();
        record.attrs().forEach((k, v) -> {
            if (!isLedgerKey(k)) {
                overflow.put(k, v);
            }
        });
        return new ExtInteractionWriteRow(
                Ids.newId("ext"),
                now,
                record.direction().name(),
                record.kind(),
                blankToNull(record.counterparty()),
                blankToNull(record.endpoint()),
                blankToNull(record.operation()),
                orElse(attr(record, "auth_mode"), "NONE"),
                attr(record, "credential_id"),
                attr(record, "actor_id"),
                attr(record, "run_as"),
                traceId,
                requestId,
                blankToNull(subject),
                attr(record, "turn_id"),
                attr(record, "run_id"),
                attr(record, "change_set_id"),
                attr(record, "tool_call_id"),
                attr(record, "idempotency_key"),
                blankToNull(record.externalRef()),
                blankToNull(record.dedupeKey()),
                attr(record, "request_digest"),
                attr(record, "response_digest"),
                longAttr(record, "request_size"),
                longAttr(record, "response_size"),
                attr(record, "payload_ref"),
                attr(record, "data_label"),
                record.status().name(),
                record.httpStatus(),
                blankToNull(errorCode),
                attempt,
                record.latencyMs(),
                subject == null || subject.isBlank() ? "SEC" : "BIZ",
                overflow.isEmpty() ? null : overflow.toString()
        );
    }

    private static String attr(ExtInteractionRecord record, String key) {
        String v = record.attrs().get(key);
        return v == null || v.isBlank() ? null : v;
    }

    private static Long longAttr(ExtInteractionRecord record, String key) {
        String v = attr(record, key);
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intAttr(ExtInteractionRecord record, String key, int fallback) {
        String v = attr(record, key);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean isLedgerKey(String key) {
        for (String k : LEDGER_KEYS) {
            if (k.equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static String orElse(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
