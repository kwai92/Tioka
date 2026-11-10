package com.tioka.governance.application.autonomy;

import com.tioka.governance.infrastructure.persistence.TrustAutonomyDO;
import com.tioka.governance.infrastructure.persistence.TrustAutonomyEventDO;
import com.tioka.governance.infrastructure.persistence.TrustAutonomyStatsDO;

import java.time.LocalDate;
import java.util.List;

/**
 * 自治等级存取面（V203 三表）。生产实现 {@code TrustAutonomyRepository}（MyBatis-Plus）；
 * 离线测试用内存实现，避免依赖真实容器。
 */
public interface AutonomyLevelStore {

    TrustAutonomyDO find(String agentId, String actionClass);

    List<TrustAutonomyDO> listByAgent(String agentId);

    void upsert(TrustAutonomyDO row);

    void insertEvent(TrustAutonomyEventDO row);

    TrustAutonomyStatsDO findStats(String agentId, String actionClass, LocalDate day);

    void upsertStats(TrustAutonomyStatsDO row);
}
