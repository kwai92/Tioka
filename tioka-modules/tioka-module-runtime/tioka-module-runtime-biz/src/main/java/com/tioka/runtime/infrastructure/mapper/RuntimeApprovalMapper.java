package com.tioka.runtime.infrastructure.mapper;

import com.tioka.runtime.infrastructure.persistence.RuntimeApprovalDO;
import com.tioka.runtime.infrastructure.persistence.WaitingTurnRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RuntimeApprovalMapper {

        RuntimeApprovalDO findById(String id);

        RuntimeApprovalDO findForUpdate(String id);

        void insert(RuntimeApprovalDO row);

        int decide(@Param("id") String id,
               @Param("status") String status,
               @Param("decidedBy") String decidedBy,
               @Param("answerJson") String answerJson,
               @Param("updateBy") String updateBy);

        List<RuntimeApprovalDO> listBySubject(@Param("subjectType") String subjectType,
                                          @Param("subjectId") String subjectId);

        int countPending(@Param("subjectType") String subjectType, @Param("subjectId") String subjectId);

        RuntimeApprovalDO findByItemId(String itemId);

        List<String> expireDue(@Param("now") LocalDateTime now, @Param("decidedBy") String decidedBy);

        int expirePendingForSubject(@Param("subjectType") String subjectType,
                                @Param("subjectId") String subjectId,
                                @Param("decidedBy") String decidedBy);

        List<WaitingTurnRow> findWaitingWithoutPending();
}
