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

    /** OBS-05：转发互斥键（PG advisory lock，双实例下保证 SIEM 不收重复记录）。 */
    static final String FORWARD_LOCK_KEY = "audit-forward-siem";
    /** OBS-05：水位通道名（audit_forward_progress PK）。 */
    static final String FORWARD_CHANNEL = "siem";

    private final AuditEventRepository repository;
    private final AuditSink sink;
    private final AuditProperties props;

    public AuditForwarder(AuditEventRepository repository, AuditSink sink, AuditProperties props) {
        this.repository = repository;
        this.sink = sink;
        this.props = props;
    }

    /**
     * 批量外送（GOV-02）+ OBS-05 互斥与水位：
     * PG advisory lock（同事务同连接）保证双实例只有一方外送；未取得锁直接跳过本轮。
     * 成功后推进 {@code audit_forward_progress} 水位（本批最后一条记录 ID）。
     */
    @Scheduled(fixedDelayString = "${tioka.audit.forward-delay-ms:5000}")
    @Transactional
    public void forwardBatch() {
        if (!repository.tryAdvisoryLock(FORWARD_LOCK_KEY)) {
            log.debug("audit forward skipped: another instance holds the advisory lock");
            return;
        }
        try {
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
            if (!done.isEmpty()) {
                repository.updateForwardProgress(FORWARD_CHANNEL,
                        done.get(done.size() - 1), done.size());
            }
        } finally {
            repository.advisoryUnlock(FORWARD_LOCK_KEY);
        }
    }

    @Scheduled(cron = "${tioka.audit.retention-cron:0 30 3 * * *}")
    @Transactional
    public void purgeExpired() {
        if (!props.isPhysicalPurgeEnabled()) {
            log.debug("audit physical purge disabled (OBS-01 / GOV-09 pending)");
            return;
        }
        // 仅显式打开时执行；生产在 GOV-09 前不得打开
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
