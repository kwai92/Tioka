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
}
