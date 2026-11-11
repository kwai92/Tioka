package com.tioka.infra.infrastructure;

import com.tioka.infra.infrastructure.mapper.NtfDeliveryMapper;
import com.tioka.infra.infrastructure.persistence.NtfDeliveryDO;

import java.time.Instant;

/** ntf_delivery 仓储（NTF-01）。经 InfraNotifyAutoConfiguration 条件装配（tioka.notify.enabled）。 */
public class NtfDeliveryRepository {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_UPDATED = "UPDATED";

    private final NtfDeliveryMapper mapper;

    public NtfDeliveryRepository(NtfDeliveryMapper mapper) {
        this.mapper = mapper;
    }

    /** 按 dedup_key 幂等插入；返回是否新插入（false = 去重命中，跳过发送）。 */
    public boolean insertIfAbsent(NtfDeliveryDO row) {
        return mapper.insertIfAbsent(row) > 0;
    }

    public NtfDeliveryDO findById(String deliveryId) {
        return mapper.findById(deliveryId);
    }

    public boolean markSent(String deliveryId, String externalRef, Instant sentAt) {
        return mapper.markSent(deliveryId, externalRef, sentAt) > 0;
    }

    public boolean markFailed(String deliveryId, String lastError) {
        return mapper.markFailed(deliveryId, truncate(lastError)) > 0;
    }

    /** 瞬态失败记账（须在 REQUIRES_NEW 事务中调用，重试回滚后仍保留）。返回累计尝试次数。 */
    public int recordAttempt(String deliveryId, String lastError) {
        mapper.recordAttempt(deliveryId, truncate(lastError));
        NtfDeliveryDO row = mapper.findById(deliveryId);
        return row == null || row.getAttempts() == null ? 0 : row.getAttempts();
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }
}
