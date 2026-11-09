package com.tioka.runtime.infrastructure;

import com.tioka.runtime.infrastructure.mapper.TrustActionMapper;
import com.tioka.runtime.infrastructure.persistence.TrustActionDO;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * trust_action 仓储（V144 / TRU-05）。application 经本类访问，禁止直持 Mapper。
 */
@Repository
public class TrustActionRepository {
    private final TrustActionMapper mapper;

    public TrustActionRepository(TrustActionMapper mapper) {
        this.mapper = mapper;
    }

    public TrustActionDO findById(String id) {
        return mapper.findById(id);
    }

    public TrustActionDO findByApprovalId(String approvalId) {
        return mapper.findByApprovalId(approvalId);
    }

    public void linkApproval(String id, String approvalId) {
        mapper.linkApproval(id, approvalId);
    }

    public TrustActionDO findForUpdate(String id) {
        return mapper.findForUpdate(id);
    }

    public void insert(TrustActionDO row) {
        mapper.insert(row);
    }

    public int markStatus(String id, String fromStatus, String toStatus,
                          String receiptJson, LocalDateTime undoDeadline) {
        return mapper.markStatus(id, fromStatus, toStatus, receiptJson, undoDeadline);
    }

    public int nextSeq(String turnId) {
        return mapper.nextSeq(turnId);
    }

    /** TRU-02 当日累计重算：当天 APPLIED 且未撤销的动作（Redis 丢失时从 trust_action 重算）。 */
    public java.util.List<TrustActionDO> listAppliedInDay(String agentId, String actionClass,
                                                          LocalDateTime fromUtc, LocalDateTime toUtc) {
        return mapper.listAppliedInDay(agentId, actionClass, fromUtc, toUtc);
    }
}
