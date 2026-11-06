package com.tioka.system.application.audit;

import com.tioka.system.api.spi.AuditSink;
import com.tioka.system.config.AuditProperties;
import com.tioka.system.infrastructure.AuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将未转发的 {@code audit_event} 批量写入 {@link AuditSink}，成功后置 {@code forwarded=true}（GOV-02）。
 */
@Component
public class AuditForwarder {
    private static final Logger log = LoggerFactory.getLogger(AuditForwarder.class);

    private final AuditEventRepository repository;
    private final AuditSink sink;
    private final AuditProperties props;

    public AuditForwarder(AuditEventRepository repository, AuditSink sink, AuditProperties props) {
        this.repository = repository;
        this.sink = sink;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${tioka.audit.forward-delay-ms:5000}")
    @Transactional
    public void forwardBatch() {
        int batch = Math.max(1, props.getForwardBatchSize());
        List<Map<String, Object>> rows = repository.listUnforwarded(batch);
        if (rows.isEmpty()) {
            return;
        }
        List<String> done = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String id = str(row.get("id"));
            try {
                Map<String, Object> detail = new LinkedHashMap<>(row);
                sink.emit(str(row.get("action")), str(row.get("actorId")), detail);
                done.add(id);
            } catch (RuntimeException ex) {
                log.warn("audit forward failed id={}: {}", id, ex.toString());
                break;
            }
        }
        repository.markForwarded(done);
    }

    @Scheduled(cron = "${tioka.audit.retention-cron:0 30 3 * * *}")
    @Transactional
    public void purgeExpired() {
        int days = Math.max(1, props.getRetentionDays());
        Instant before = Instant.now().minus(days, ChronoUnit.DAYS);
        int n = repository.deleteExpiredForwarded(before);
        if (n > 0) {
            log.info("audit retention purged {} rows older than {} days", n, days);
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
