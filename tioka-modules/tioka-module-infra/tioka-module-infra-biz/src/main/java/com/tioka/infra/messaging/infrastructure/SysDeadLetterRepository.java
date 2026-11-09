package com.tioka.infra.messaging.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tioka.infra.messaging.infrastructure.mapper.SysDeadLetterMapper;
import com.tioka.infra.messaging.infrastructure.persistence.SysDeadLetterDO;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** {@code sys_dead_letter}（V130，平台级技术表，无 workspace_id）。 */
@Repository
public class SysDeadLetterRepository {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_REPLAYED = "REPLAYED";
    public static final String STATUS_DISCARDED = "DISCARDED";

    private final SysDeadLetterMapper mapper;

    public SysDeadLetterRepository(SysDeadLetterMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(SysDeadLetterDO row) {
        mapper.insert(row);
    }

    public SysDeadLetterDO findById(String id) {
        return mapper.selectById(id);
    }

    /** 运维列表：按 consumer/status 过滤（null 不过滤），create_at 倒序，offset/size 由调用方计算。 */
    public List<SysDeadLetterDO> list(String consumer, String status, int offset, int size) {
        LambdaQueryWrapper<SysDeadLetterDO> query = new LambdaQueryWrapper<SysDeadLetterDO>()
                .eq(consumer != null && !consumer.isBlank(), SysDeadLetterDO::getConsumer, consumer)
                .eq(status != null && !status.isBlank(), SysDeadLetterDO::getStatus, status)
                .orderByDesc(SysDeadLetterDO::getCreateAt)
                .last("LIMIT " + size + " OFFSET " + offset);
        return mapper.selectList(query);
    }

    public long count(String consumer, String status) {
        return mapper.selectCount(new LambdaQueryWrapper<SysDeadLetterDO>()
                .eq(consumer != null && !consumer.isBlank(), SysDeadLetterDO::getConsumer, consumer)
                .eq(status != null && !status.isBlank(), SysDeadLetterDO::getStatus, status));
    }

    /**
     * 终态流转统一走条件更新（仅 OPEN 可流转），天然幂等：
     * 已 REPLAYED/DISCARDED 的行条件不命中，返回 false 由上层拒绝重复操作。
     */
    public boolean markReplayed(String id, String handledBy, Instant handledAt) {
        return mapper.update(null, new LambdaUpdateWrapper<SysDeadLetterDO>()
                .eq(SysDeadLetterDO::getId, id)
                .eq(SysDeadLetterDO::getStatus, STATUS_OPEN)
                .set(SysDeadLetterDO::getStatus, STATUS_REPLAYED)
                .set(SysDeadLetterDO::getHandledBy, handledBy)
                .set(SysDeadLetterDO::getHandledAt, handledAt)) == 1;
    }

    public boolean markDiscarded(String id, String handledBy, Instant handledAt, String error) {
        return mapper.update(null, new LambdaUpdateWrapper<SysDeadLetterDO>()
                .eq(SysDeadLetterDO::getId, id)
                .eq(SysDeadLetterDO::getStatus, STATUS_OPEN)
                .set(SysDeadLetterDO::getStatus, STATUS_DISCARDED)
                .set(SysDeadLetterDO::getHandledBy, handledBy)
                .set(SysDeadLetterDO::getHandledAt, handledAt)
                .set(SysDeadLetterDO::getError, error)) == 1;
    }

    /** 重放仍失败：保持 OPEN，回写错误并累加 attempts。 */
    public boolean markRetryFailed(String id, String error, int attempts) {
        return mapper.update(null, new LambdaUpdateWrapper<SysDeadLetterDO>()
                .eq(SysDeadLetterDO::getId, id)
                .eq(SysDeadLetterDO::getStatus, STATUS_OPEN)
                .set(SysDeadLetterDO::getError, error)
                .set(SysDeadLetterDO::getAttempts, attempts)) == 1;
    }
}
