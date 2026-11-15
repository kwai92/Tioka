package com.tioka.runtime.kernel.infrastructure;

import com.tioka.runtime.infrastructure.mapper.RuntimeApprovalMapper;
import com.tioka.runtime.infrastructure.persistence.RuntimeApprovalDO;
import com.tioka.runtime.infrastructure.persistence.WaitingTurnRow;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class ApprovalRepository {
    private final RuntimeApprovalMapper mapper;

    public ApprovalRepository(RuntimeApprovalMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<RuntimeApprovalDO> findById(String id) {
        return Optional.ofNullable(mapper.findById(id));
    }

    public Optional<RuntimeApprovalDO> findForUpdate(String id) {
        return Optional.ofNullable(mapper.findForUpdate(id));
    }

    public void insert(RuntimeApprovalDO row) {
        mapper.insert(row);
    }

    public int decide(String id, String status, String decidedBy, String answerJson) {
        return mapper.decide(id, status, decidedBy, answerJson, decidedBy);
    }

    public List<RuntimeApprovalDO> listBySubject(String subjectType, String subjectId) {
        return mapper.listBySubject(subjectType, subjectId);
    }

    public int countPending(String subjectType, String subjectId) {
        return mapper.countPending(subjectType, subjectId);
    }

    public Optional<RuntimeApprovalDO> findByItemId(String itemId) {
        return Optional.ofNullable(mapper.findByItemId(itemId));
    }

    @Transactional
    public List<String> expireDue(Instant now) {
        return mapper.expireDue(LocalDateTime.ofInstant(now, ZoneOffset.UTC), "system");
    }

    public int expirePendingForSubject(String subjectType, String subjectId, String decidedBy) {
        return mapper.expirePendingForSubject(subjectType, subjectId, decidedBy);
    }

    public List<WaitingTurnRow> findWaitingWithoutPending() {
        return mapper.findWaitingWithoutPending();
    }

    /** OBS-08 时间线重建：按时间段读回审批（ADR-075 §2）。 */
    public List<RuntimeApprovalDO> listByRange(Instant from, Instant to) {
        return mapper.listByRange(LocalDateTime.ofInstant(from, ZoneOffset.UTC),
                LocalDateTime.ofInstant(to, ZoneOffset.UTC));
    }
}
