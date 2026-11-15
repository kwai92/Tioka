package com.tioka.runtime.infrastructure.mapper;

import com.tioka.runtime.infrastructure.persistence.RuntimeHumanTaskDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SCH-09 / ADR-066 §7.4：人工补录任务（runtime_human_task，V233）。
 */
@Mapper
public interface RuntimeHumanTaskMapper {

    void insert(RuntimeHumanTaskDO row);

    RuntimeHumanTaskDO findById(String id);

    RuntimeHumanTaskDO findForUpdate(String id);

    int countPending(@Param("subjectType") String subjectType, @Param("subjectId") String subjectId);

    List<RuntimeHumanTaskDO> listBySubject(@Param("subjectType") String subjectType,
                                           @Param("subjectId") String subjectId);

    /** 该主体最新一条已提交（SUBMITTED）补录任务；续跑时消费。 */
    RuntimeHumanTaskDO findLatestSubmitted(@Param("subjectType") String subjectType,
                                           @Param("subjectId") String subjectId);

    /** 人提交补录数据：PENDING → SUBMITTED。 */
    int submit(@Param("id") String id,
               @Param("decidedBy") String decidedBy,
               @Param("answerJson") String answerJson,
               @Param("updateBy") String updateBy);

    /** 超时过期：PENDING → EXPIRED；返回受影响任务 ID（结局 HUMAN_TIMEOUT）。 */
    List<String> expireDue(@Param("now") LocalDateTime now, @Param("decidedBy") String decidedBy);

    /** 主体终结（取消/失败）时清掉未决补录任务。 */
    int cancelPendingForSubject(@Param("subjectType") String subjectType,
                                @Param("subjectId") String subjectId,
                                @Param("decidedBy") String decidedBy);
}
