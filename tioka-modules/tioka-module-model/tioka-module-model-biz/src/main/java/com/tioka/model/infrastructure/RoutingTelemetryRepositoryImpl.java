package com.tioka.model.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.model.infrastructure.mapper.ModelCompetenceMapper;
import com.tioka.model.infrastructure.mapper.ModelRouteEventMapper;
import com.tioka.model.infrastructure.persistence.ModelCompetenceDO;
import com.tioka.model.infrastructure.persistence.ModelRouteEventDO;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class RoutingTelemetryRepositoryImpl implements RoutingTelemetryRepository {

    private final ModelRouteEventMapper events;
    private final ModelCompetenceMapper competence;

    public RoutingTelemetryRepositoryImpl(ModelRouteEventMapper events, ModelCompetenceMapper competence) {
        this.events = events;
        this.competence = competence;
    }

    @Override
    public void insertEvent(ModelRouteEventDO row) {
        events.insert(row);
    }

    @Override
    public Optional<ModelRouteEventDO> latestSelection(String threadId) {
        List<ModelRouteEventDO> rows = events.selectList(Wrappers.<ModelRouteEventDO>lambdaQuery()
                .eq(ModelRouteEventDO::getThreadId, threadId)
                .in(ModelRouteEventDO::getKind, "SELECT", "REUSE")
                .orderByDesc(ModelRouteEventDO::getCreateAt)
                .last("LIMIT 1"));
        return rows.stream().findFirst();
    }

    @Override
    public List<ModelRouteEventDO> eventsByTurn(String turnId) {
        return events.selectList(Wrappers.<ModelRouteEventDO>lambdaQuery()
                .eq(ModelRouteEventDO::getTurnId, turnId)
                .orderByAsc(ModelRouteEventDO::getCreateAt)
                .orderByAsc(ModelRouteEventDO::getId));
    }

    @Override
    public void insertCompetence(ModelCompetenceDO row) {
        competence.insert(row);
    }

    @Override
    public boolean hasCompetence(String turnId, String bindingId) {
        return competence.selectCount(Wrappers.<ModelCompetenceDO>lambdaQuery()
                .eq(ModelCompetenceDO::getTurnId, turnId)
                .eq(ModelCompetenceDO::getBindingId, bindingId)) > 0;
    }

    @Override
    public Optional<Double> latestCompetenceScore(String bindingId) {
        List<ModelCompetenceDO> rows = competence.selectList(Wrappers.<ModelCompetenceDO>lambdaQuery()
                .eq(ModelCompetenceDO::getBindingId, bindingId)
                .isNotNull(ModelCompetenceDO::getScore)
                .orderByDesc(ModelCompetenceDO::getCreateAt)
                .last("LIMIT 1"));
        return rows.stream().findFirst().map(ModelCompetenceDO::getScore);
    }
}
