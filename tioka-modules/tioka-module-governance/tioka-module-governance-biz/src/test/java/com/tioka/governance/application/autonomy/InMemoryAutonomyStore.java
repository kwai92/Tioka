package com.tioka.governance.application.autonomy;

import com.tioka.governance.api.autonomy.AutonomyApi;
import com.tioka.governance.api.autonomy.AutonomyContext;
import com.tioka.governance.api.autonomy.AutonomyDecision;
import com.tioka.governance.api.autonomy.AutonomyGate;
import com.tioka.governance.api.autonomy.AutonomyLevel;
import com.tioka.governance.api.policy.PolicyDecision;
import com.tioka.governance.infrastructure.persistence.TrustAutonomyDO;
import com.tioka.governance.infrastructure.persistence.TrustAutonomyEventDO;
import com.tioka.governance.infrastructure.persistence.TrustAutonomyStatsDO;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** TRU-09 离线测试支撑：内存版等级存取。 */
final class InMemoryAutonomyStore implements AutonomyLevelStore {

    final Map<String, TrustAutonomyDO> levels = new LinkedHashMap<>();
    final List<TrustAutonomyEventDO> events = new ArrayList<>();
    final Map<String, TrustAutonomyStatsDO> stats = new LinkedHashMap<>();
    final AtomicInteger seq = new AtomicInteger();

    @Override
    public TrustAutonomyDO find(String agentId, String actionClass) {
        return levels.get(key(agentId, actionClass));
    }

    @Override
    public List<TrustAutonomyDO> listByAgent(String agentId) {
        List<TrustAutonomyDO> out = new ArrayList<>();
        for (Map.Entry<String, TrustAutonomyDO> e : levels.entrySet()) {
            if (e.getKey().startsWith(agentId + ":")) {
                out.add(e.getValue());
            }
        }
        return out;
    }

    @Override
    public void upsert(TrustAutonomyDO row) {
        levels.put(key(row.getAgentId(), row.getActionClass()), row);
    }

    @Override
    public void insertEvent(TrustAutonomyEventDO row) {
        events.add(row);
    }

    @Override
    public TrustAutonomyStatsDO findStats(String agentId, String actionClass, LocalDate day) {
        return stats.get(agentId + ":" + actionClass + ":" + day);
    }

    @Override
    public void upsertStats(TrustAutonomyStatsDO row) {
        stats.put(row.getAgentId() + ":" + row.getActionClass() + ":" + row.getDay(), row);
    }

    static String key(String agentId, String actionClass) {
        return agentId + ":" + actionClass;
    }
}
