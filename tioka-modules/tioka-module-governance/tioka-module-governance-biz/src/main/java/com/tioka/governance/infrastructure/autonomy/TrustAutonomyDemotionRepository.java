package com.tioka.governance.infrastructure.autonomy;

import com.tioka.governance.infrastructure.mapper.TrustAutonomyDemotionMapper;
import com.tioka.governance.infrastructure.persistence.TrustAutonomyDemotionDO;
import org.springframework.stereotype.Repository;

/** V226 降级幂等台账读写（TRU-10）。 */
@Repository
public class TrustAutonomyDemotionRepository {

    private final TrustAutonomyDemotionMapper mapper;

    public TrustAutonomyDemotionRepository(TrustAutonomyDemotionMapper mapper) {
        this.mapper = mapper;
    }

    /** true = 首次处理；false = 重复事件（幂等跳过）。 */
    public boolean tryReserve(String demotionKey, String workspaceId, String agentId,
                              String actionClass, String eventId, String fromLevel, String toLevel) {
        TrustAutonomyDemotionDO row = new TrustAutonomyDemotionDO();
        row.setDemotionKey(demotionKey);
        row.setWorkspaceId(workspaceId == null ? "" : workspaceId);
        row.setAgentId(agentId == null ? "" : agentId);
        row.setActionClass(actionClass == null ? "" : actionClass);
        row.setEventId(eventId);
        row.setFromLevel(fromLevel);
        row.setToLevel(toLevel);
        return mapper.tryInsert(row) == 1;
    }
}
