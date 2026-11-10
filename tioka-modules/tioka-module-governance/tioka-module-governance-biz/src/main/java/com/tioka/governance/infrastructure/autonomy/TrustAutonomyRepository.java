package com.tioka.governance.infrastructure.autonomy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tioka.governance.infrastructure.mapper.TrustAutonomyEventMapper;
import com.tioka.governance.infrastructure.mapper.TrustAutonomyMapper;
import com.tioka.governance.infrastructure.mapper.TrustAutonomyStatsMapper;
import com.tioka.governance.infrastructure.persistence.TrustAutonomyDO;
import com.tioka.governance.infrastructure.persistence.TrustAutonomyEventDO;
import com.tioka.governance.application.autonomy.AutonomyLevelStore;
import com.tioka.governance.infrastructure.persistence.TrustAutonomyStatsDO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * trust_autonomy / trust_autonomy_event / trust_autonomy_stats 读写（V203 / TRU-09）。
 * 由 {@code AutonomyAutoConfiguration} 装配，避免特性关闭时被全局 ComponentScan 拉起。
 */
public class TrustAutonomyRepository implements AutonomyLevelStore {

    private final TrustAutonomyMapper levels;
    private final TrustAutonomyEventMapper events;
    private final TrustAutonomyStatsMapper stats;

    public TrustAutonomyRepository(TrustAutonomyMapper levels,
                                   TrustAutonomyEventMapper events,
                                   TrustAutonomyStatsMapper stats) {
        this.levels = levels;
        this.events = events;
        this.stats = stats;
    }

    public TrustAutonomyDO find(String agentId, String actionClass) {
        return levels.selectOne(new LambdaQueryWrapper<TrustAutonomyDO>()
                .eq(TrustAutonomyDO::getAgentId, agentId)
                .eq(TrustAutonomyDO::getActionClass, actionClass));
    }

    public List<TrustAutonomyDO> listByAgent(String agentId) {
        return levels.selectList(new LambdaQueryWrapper<TrustAutonomyDO>()
                .eq(TrustAutonomyDO::getAgentId, agentId)
                .orderByAsc(TrustAutonomyDO::getActionClass));
    }

    public void upsert(TrustAutonomyDO row) {
        TrustAutonomyDO existing = find(row.getAgentId(), row.getActionClass());
        if (existing == null) {
            levels.insert(row);
        } else {
            levels.update(null, new LambdaUpdateWrapper<TrustAutonomyDO>()
                    .eq(TrustAutonomyDO::getAgentId, row.getAgentId())
                    .eq(TrustAutonomyDO::getActionClass, row.getActionClass())
                    .set(TrustAutonomyDO::getLevel, row.getLevel())
                    .set(row.getObserveUntil() != null, TrustAutonomyDO::getObserveUntil, row.getObserveUntil())
                    .set(row.getPromotionBlockedUntil() != null, TrustAutonomyDO::getPromotionBlockedUntil,
                            row.getPromotionBlockedUntil()));
        }
    }

    public void insertEvent(TrustAutonomyEventDO row) {
        if (row.getCreateAt() == null) {
            row.setCreateAt(LocalDateTime.now());
        }
        events.insert(row);
    }

    public List<TrustAutonomyEventDO> listEvents(String agentId, String actionClass) {
        return events.selectList(new LambdaQueryWrapper<TrustAutonomyEventDO>()
                .eq(TrustAutonomyEventDO::getAgentId, agentId)
                .eq(TrustAutonomyEventDO::getActionClass, actionClass)
                .orderByDesc(TrustAutonomyEventDO::getCreateAt));
    }

    public TrustAutonomyStatsDO findStats(String agentId, String actionClass, LocalDate day) {
        return stats.selectOne(new LambdaQueryWrapper<TrustAutonomyStatsDO>()
                .eq(TrustAutonomyStatsDO::getAgentId, agentId)
                .eq(TrustAutonomyStatsDO::getActionClass, actionClass)
                .eq(TrustAutonomyStatsDO::getDay, day));
    }

    public void upsertStats(TrustAutonomyStatsDO row) {
        TrustAutonomyStatsDO existing = findStats(row.getAgentId(), row.getActionClass(), row.getDay());
        if (existing == null) {
            stats.insert(row);
        } else {
            stats.update(null, new LambdaUpdateWrapper<TrustAutonomyStatsDO>()
                    .eq(TrustAutonomyStatsDO::getAgentId, row.getAgentId())
                    .eq(TrustAutonomyStatsDO::getActionClass, row.getActionClass())
                    .eq(TrustAutonomyStatsDO::getDay, row.getDay())
                    .set(TrustAutonomyStatsDO::getStatsJson, row.getStatsJson()));
        }
    }
}
