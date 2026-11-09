package com.tioka.infra.messaging;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.infra.messaging.infrastructure.SysDeadLetterRepository;
import com.tioka.infra.messaging.infrastructure.persistence.SysDeadLetterDO;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * ORC-07 死信运维：列表 / 重放 / 丢弃（FE-17 的后端出口）。
 *
 * <p>重放语义（M2-01 ORC-07「管理端」节）：按既有投递通道
 * {@link IdempotentDeliveryService#deliver} 重新投递 payloadJson
 * （reconsumeTimes 归零，不改信封格式；原 eventId 若已被 sys_consumed
 * 记录则由消费幂等跳过副作用）。成功 → status=REPLAYED，handledBy/handledAt 回填；
 * 仍失败 → 保持 OPEN，error 回写并累加 attempts。丢弃 → status=DISCARDED。
 * 仅 OPEN 死信可重放/丢弃（条件更新保证，重复操作返回 CONFLICT）。
 *
 * <p>前提：consumer.max-attempts ≥ 2（默认 3），否则 deliver 末次失败会写新死信
 * 而非上抛，重放失败将无法被识别。该假设由默认配置满足，运维不得配置为 1。
 */
@Service
public class DeadLetterOpsService {

    /** 丢弃原因落入 error 列的前缀（表无独立 reason 列，V130 结构零迁移）。 */
    private static final String DISCARD_PREFIX = "[discarded:";
    private static final int MAX_ERROR_LEN = 2000;

    private final IdempotentDeliveryService delivery;
    private final SysDeadLetterRepository deadLetters;

    public DeadLetterOpsService(IdempotentDeliveryService delivery, SysDeadLetterRepository deadLetters) {
        this.delivery = delivery;
        this.deadLetters = deadLetters;
    }

    public record Page<T>(List<T> items, long total, int page, int size) {}

    public Page<SysDeadLetterDO> list(String consumer, String status, int page, int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 100);
        List<SysDeadLetterDO> items = deadLetters.list(consumer, status, (p - 1) * s, s);
        return new Page<>(items, deadLetters.count(consumer, status), p, s);
    }

    public SysDeadLetterDO replay(String id, String handledBy) {
        SysDeadLetterDO row = requireOpen(id);
        try {
            delivery.deliver(row.getConsumer(), row.getTopic(), row.getTag(), row.getPayloadJson(), 0);
        } catch (RuntimeException e) {
            deadLetters.markRetryFailed(id, truncate(e.toString()), row.getAttempts() + 1);
            throw new BizException(ErrorCode.CONFLICT, "重放仍失败，死信保持 OPEN: " + e.getMessage());
        }
        if (!deadLetters.markReplayed(id, handledBy, Instant.now())) {
            throw new BizException(ErrorCode.CONFLICT, "死信状态已变化，重放未生效");
        }
        return deadLetters.findById(id);
    }

    public SysDeadLetterDO discard(String id, String handledBy, String reason) {
        SysDeadLetterDO row = requireOpen(id);
        String cause = reason == null || reason.isBlank() ? "no-reason" : reason;
        String error = DISCARD_PREFIX + cause + "]"
                + (row.getError() == null ? "" : " " + row.getError());
        if (!deadLetters.markDiscarded(id, handledBy, Instant.now(), truncate(error))) {
            throw new BizException(ErrorCode.CONFLICT, "死信状态已变化，丢弃未生效");
        }
        return deadLetters.findById(id);
    }

    private SysDeadLetterDO requireOpen(String id) {
        SysDeadLetterDO row = deadLetters.findById(id);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "死信不存在: " + id);
        }
        if (!SysDeadLetterRepository.STATUS_OPEN.equals(row.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT,
                    "仅 OPEN 死信可操作，当前 " + row.getStatus());
        }
        return row;
    }

    private static String truncate(String s) {
        if (s == null || s.length() <= MAX_ERROR_LEN) {
            return s;
        }
        return s.substring(0, MAX_ERROR_LEN);
    }
}
