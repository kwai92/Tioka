package com.tioka.ontology.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.ontology.infrastructure.persistence.LeaseReviewRoundDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@code lease_review_round}（V175）。复合主键 (review_run_id, round)；
 * 无 workspace_id 列，旁路 ScopeLine。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface LeaseReviewRoundMapper extends BaseMapper<LeaseReviewRoundDO> {

    /** upsert：ON CONFLICT (review_run_id, round) DO UPDATE（轮次幂等覆盖）。 */
    int upsert(@Param("row") LeaseReviewRoundDO row);

    LeaseReviewRoundDO findByRunAndRound(@Param("reviewRunId") String reviewRunId,
                                         @Param("round") int round);

    /** 该审查运行的当前最大轮次（无行返回 null）。 */
    Integer maxRound(@Param("reviewRunId") String reviewRunId);
}
