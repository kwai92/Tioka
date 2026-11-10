package com.tioka.runtime.application.trust;

import com.tioka.runtime.infrastructure.persistence.TrustActionDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * trust_action 存取面（V144 + V202 列）。生产实现 {@code TrustActionRepository}（MyBatis-Plus XML）；
 * 撤销 / 补偿链（{@code UndoService} / {@code TrustCompensator} / {@code PreparedCallStore}）
 * 依赖本接口，离线测试用内存实现。
 */
public interface TrustActionStore {

    TrustActionDO findById(String id);

    TrustActionDO findByApprovalId(String approvalId);

    void linkApproval(String id, String approvalId);

    TrustActionDO findForUpdate(String id);

    void insert(TrustActionDO row);

    /** CAS 状态流转：fromStatus 匹配才更新；返回影响行数。 */
    int markStatus(String id, String fromStatus, String toStatus,
                   String receiptJson, LocalDateTime undoDeadline);

    int nextSeq(String turnId);

    /** TRU-02 当日累计重算数据源。 */
    List<TrustActionDO> listAppliedInDay(String agentId, String actionClass,
                                         LocalDateTime fromUtc, LocalDateTime toUtc);

    // ---------- TRU-07 撤销 / 补偿 ----------

    /** 范围内全部动作按 seq 倒序（撤销选动作 + 鉴权取数）。 */
    List<TrustActionDO> listAppliedInScope(String scopeType, String scopeId);

    /** 同组 COMPENSATING 动作按 seq 正序。 */
    List<TrustActionDO> listCompensatingInScope(String scopeType, String scopeId);

    /** 标 COMPENSATING 回填范围与补偿顺序号。 */
    void markCompensatingMeta(String id, String scopeType, String scopeId, int seq);

    /** 补偿重试计数 +1，返回累计次数。 */
    int bumpUndoAttempts(String id);

    /** 窗口内 COMPENSATED 动作数（撤销熔断计数数据源）。 */
    int countCompensatedInWindow(String agentId, LocalDateTime fromUtc, LocalDateTime toUtc);
}
