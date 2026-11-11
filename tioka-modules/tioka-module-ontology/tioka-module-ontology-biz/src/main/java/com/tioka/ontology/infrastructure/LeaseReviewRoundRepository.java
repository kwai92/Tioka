package com.tioka.ontology.infrastructure;

import com.tioka.ontology.infrastructure.mapper.LeaseReviewRoundMapper;
import com.tioka.ontology.infrastructure.persistence.LeaseReviewRoundDO;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * {@code lease_review_round} 仓储（LSE-06 审查轮次，V175）。upsert 覆盖写（幂等）。
 */
@Repository
public class LeaseReviewRoundRepository {

    private final LeaseReviewRoundMapper mapper;

    public LeaseReviewRoundRepository(LeaseReviewRoundMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<LeaseReviewRoundDO> find(String reviewRunId, int round) {
        return Optional.ofNullable(mapper.findByRunAndRound(reviewRunId, round));
    }

    /** upsert：ON CONFLICT (review_run_id, round) DO UPDATE。 */
    public void upsert(LeaseReviewRoundDO row) {
        mapper.upsert(row);
    }

    /** 当前最大轮次；无行时 0（下一轮 = 1）。 */
    public int currentRound(String reviewRunId) {
        Integer max = mapper.maxRound(reviewRunId);
        return max == null ? 0 : max;
    }
}
