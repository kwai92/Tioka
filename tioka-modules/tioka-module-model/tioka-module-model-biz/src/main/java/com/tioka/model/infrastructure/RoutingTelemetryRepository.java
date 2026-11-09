package com.tioka.model.infrastructure;

import com.tioka.model.infrastructure.persistence.ModelCompetenceDO;
import com.tioka.model.infrastructure.persistence.ModelRouteEventDO;

import java.util.List;
import java.util.Optional;

/**
 * MH-06 路由时间线与胜任度持久化契约（V214）。
 * 三层封装：application 经 {@code RoutingTelemetry} 访问，禁直持 Mapper（ArchUnit）。
 */
public interface RoutingTelemetryRepository {

    void insertEvent(ModelRouteEventDO row);

    /** 线程内最近一条选中/沿用事件（SELECT / REUSE），供 preferPreviousInGroup 沿用判断。 */
    Optional<ModelRouteEventDO> latestSelection(String threadId);

    List<ModelRouteEventDO> eventsByTurn(String turnId);

    void insertCompetence(ModelCompetenceDO row);

    /** 同 Turn 已有打分时返回 true（UNIQUE(turn_id) 幂等保护）。 */
    boolean hasCompetence(String turnId, String bindingId);

    /** binding 最近一次胜任度分（作为下一次决策的 previousCompetence 输入）。 */
    Optional<Double> latestCompetenceScore(String bindingId);
}
