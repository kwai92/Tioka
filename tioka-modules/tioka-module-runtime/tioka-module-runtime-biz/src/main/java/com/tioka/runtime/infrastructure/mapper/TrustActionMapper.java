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
}
