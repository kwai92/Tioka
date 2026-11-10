package com.tioka.runtime.infrastructure.mapper;

import com.tioka.runtime.infrastructure.persistence.TrustActionDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TrustActionMapper {

    TrustActionDO findById(String id);

    TrustActionDO findByApprovalId(String approvalId);

    void linkApproval(@Param("id") String id, @Param("approvalId") String approvalId);

    TrustActionDO findForUpdate(String id);

    void insert(TrustActionDO row);

    int markStatus(@Param("id") String id,
                   @Param("fromStatus") String fromStatus,
                   @Param("toStatus") String toStatus,
                   @Param("receiptJson") String receiptJson,
                   @Param("undoDeadline") java.time.LocalDateTime undoDeadline);

    int nextSeq(@Param("turnId") String turnId);

    /** TRU-02 当日累计重算：当天 APPLIED 且未撤销的动作（Redis 丢失时从 trust_action 重算）。 */
    java.util.List<TrustActionDO> listAppliedInDay(@Param("agentId") String agentId,
                                                   @Param("actionClass") String actionClass,
                                                   @Param("fromUtc") java.time.LocalDateTime fromUtc,
                                                   @Param("toUtc") java.time.LocalDateTime toUtc);

    // ---------- TRU-07 撤销 / 补偿 ----------

    /** 范围内 APPLIED 动作按 seq 倒序（撤销选动作）。 */
    java.util.List<TrustActionDO> listAppliedInScope(@Param("scopeType") String scopeType,
                                                     @Param("scopeId") String scopeId);

    /** 同组 COMPENSATING 动作按 seq 正序。 */
    java.util.List<TrustActionDO> listCompensatingInScope(@Param("scopeType") String scopeType,
                                                          @Param("scopeId") String scopeId);

    /** 标 COMPENSATING 回填范围与补偿顺序号。 */
    void markCompensatingMeta(@Param("id") String id,
                              @Param("scopeType") String scopeType,
                              @Param("scopeId") String scopeId,
                              @Param("seq") int seq);

    /** 补偿重试计数 +1，返回累计次数。 */
    int bumpUndoAttempts(@Param("id") String id);

    /** 窗口内 COMPENSATED 动作数（撤销熔断计数数据源）。 */
    int countCompensatedInWindow(@Param("agentId") String agentId,
                                 @Param("fromUtc") java.time.LocalDateTime fromUtc,
                                 @Param("toUtc") java.time.LocalDateTime toUtc);
}
