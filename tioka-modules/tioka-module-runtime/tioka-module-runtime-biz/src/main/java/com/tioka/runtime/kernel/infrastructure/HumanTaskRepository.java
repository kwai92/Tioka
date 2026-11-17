package com.tioka.runtime.kernel.infrastructure;

import com.tioka.runtime.infrastructure.mapper.RuntimeHumanTaskMapper;
import com.tioka.runtime.infrastructure.persistence.RuntimeHumanTaskDO;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * SCH-09 / ADR-066 §7.4：人工补录任务仓储（runtime_human_task，V233）。
 */
@Repository
public class HumanTaskRepository {
    private final RuntimeHumanTaskMapper mapper;

    public HumanTaskRepository(RuntimeHumanTaskMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(RuntimeHumanTaskDO row) {
        mapper.insert(row);
    }

    public Optional<RuntimeHumanTaskDO> findById(String id) {
        return Optional.ofNullable(mapper.findById(id));
    }

    public Optional<RuntimeHumanTaskDO> findForUpdate(String id) {
        return Optional.ofNullable(mapper.findForUpdate(id));
    }

    public int countPending(String subjectType, String subjectId) {
        return mapper.countPending(subjectType, subjectId);
    }

    public List<RuntimeHumanTaskDO> listBySubject(String subjectType, String subjectId) {
        return mapper.listBySubject(subjectType, subjectId);
    }

    /** 该主体最新一条已提交（SUBMITTED）补录任务；续跑时消费。 */
    public Optional<RuntimeHumanTaskDO> findLatestSubmitted(String subjectType, String subjectId) {
        return Optional.ofNullable(mapper.findLatestSubmitted(subjectType, subjectId));
    }

    /** 人提交补录数据（PENDING → SUBMITTED）。 */
    public int submit(String id, String decidedBy, String answerJson) {
        return mapper.submit(id, decidedBy, answerJson, decidedBy);
    }

    /** 超时过期（PENDING → EXPIRED）；返回受影响任务 ID。 */
    public List<String> expireDue(Instant now) {
        return mapper.expireDue(LocalDateTime.ofInstant(now, ZoneOffset.UTC), "system");
    }

    public int cancelPendingForSubject(String subjectType, String subjectId, String decidedBy) {
        return mapper.cancelPendingForSubject(subjectType, subjectId, decidedBy);
    }
}
