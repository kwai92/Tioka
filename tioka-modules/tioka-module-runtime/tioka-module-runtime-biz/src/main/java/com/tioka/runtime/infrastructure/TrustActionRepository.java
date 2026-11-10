package com.tioka.runtime.infrastructure;

import com.tioka.runtime.infrastructure.mapper.TrustActionMapper;
import com.tioka.runtime.application.trust.TrustActionStore;
import com.tioka.runtime.infrastructure.persistence.TrustActionDO;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * trust_action 仓储（V144 / TRU-05）。application 经本类访问，禁止直持 Mapper。
 */
@Repository
public class TrustActionRepository implements TrustActionStore {
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

    // ---------- TRU-07 撤销 / 补偿 ----------

    /** 范围内的 APPLIED 动作按 seq 倒序（撤销流程选动作）。 */
    public java.util.List<TrustActionDO> listAppliedInScope(String scopeType, String scopeId) {
        return mapper.listAppliedInScope(scopeType, scopeId);
    }

    /** 同组全部 COMPENSATING 动作按 seq 正序（失败停组 / 展示）。 */
    public java.util.List<TrustActionDO> listCompensatingInScope(String scopeType, String scopeId) {
        return mapper.listCompensatingInScope(scopeType, scopeId);
    }

    /** 标 COMPENSATING 时回填范围与补偿顺序号。 */
    public void markCompensatingMeta(String id, String scopeType, String scopeId, int seq) {
        mapper.markCompensatingMeta(id, scopeType, scopeId, seq);
    }

    /** 补偿重试计数 +1，返回累计次数。 */
    public int bumpUndoAttempts(String id) {
        return mapper.bumpUndoAttempts(id);
    }

    /** 熔断计数查询：1h 窗口内同 Agent 被撤销次数（V202 口径由调用方判定）。 */
    public int countCompensatedInWindow(String agentId, LocalDateTime fromUtc, LocalDateTime toUtc) {
        return mapper.countCompensatedInWindow(agentId, fromUtc, toUtc);
    }
}
