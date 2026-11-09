package com.tioka.model.domain;

import com.tioka.framework.id.Ids;
import com.tioka.model.infrastructure.RoutingTelemetryRepository;
import com.tioka.model.infrastructure.persistence.ModelCompetenceDO;
import com.tioka.model.infrastructure.persistence.ModelRouteEventDO;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * MH-06 路由时间线与胜任度（V214）领域门面：路由事件每步一条（ADR-024「一次选模」），
 * 胜任度 Turn 结束打 0–1 分、只记录不参与决策（MH-07，M3 口径）。
 */
@Repository
public class RoutingTelemetry {

    /** 路由事件 kind（ADR-024 model_route_event.kind）。 */
    public static final String KIND_DECIDE = "DECIDE";
    public static final String KIND_SELECT = "SELECT";
    public static final String KIND_REUSE = "REUSE";
    public static final String KIND_FAILOVER = "FAILOVER";
    public static final String KIND_FAIL = "FAIL";

    private final RoutingTelemetryRepository repo;

    public RoutingTelemetry(RoutingTelemetryRepository repo) {
        this.repo = repo;
    }

    /** 写一条路由事件（kind/mode/deciderId/decidedGroup/selectedBindingId 均可空按 kind 语义）。 */
    public RouteEvent recordEvent(String workspaceId, String threadId, String turnId,
                                  String planId, Integer planVersionNo, String kind, String mode,
                                  String deciderId, String decidedGroup, String selectedBindingId,
                                  Double confidence, String reason, Integer latencyMs) {
        ModelRouteEventDO row = new ModelRouteEventDO();
        row.setId(Ids.newId("rte"));
        row.setWorkspaceId(workspaceId);
        row.setThreadId(threadId);
        row.setTurnId(turnId);
        row.setPlanId(planId);
        row.setPlanVersionNo(planVersionNo);
        row.setKind(kind);
        row.setMode(mode);
        row.setDeciderId(deciderId);
        row.setDecidedGroup(decidedGroup);
        row.setSelectedBindingId(selectedBindingId);
        row.setConfidence(confidence);
        row.setReason(reason);
        row.setLatencyMs(latencyMs);
        repo.insertEvent(row);
        return new RouteEvent(row.getId(), kind, mode, decidedGroup, selectedBindingId, reason, latencyMs);
    }

    /** 线程内最近一次选中/沿用的 bindingId（preferPreviousInGroup 输入）；无记录为空。 */
    public Optional<String> latestSelectionBinding(String threadId) {
        return repo.latestSelection(threadId).map(ModelRouteEventDO::getSelectedBindingId);
    }

    public List<RouteEvent> eventsByTurn(String turnId) {
        return repo.eventsByTurn(turnId).stream().map(this::toEvent).toList();
    }

    private RouteEvent toEvent(ModelRouteEventDO r) {
        return new RouteEvent(r.getId(), r.getKind(), r.getMode(), r.getDecidedGroup(),
                r.getSelectedBindingId(), r.getReason(), r.getLatencyMs());
    }

    /**
     * Turn 结束胜任度打分（0–1）。同 Turn 同 binding 重复打分忽略（幂等）。
     *
     * @param source 打分来源（user_feedback / judge_model / eval 等自由标注）
     * @return 是否落库
     */
    public boolean recordCompetence(String workspaceId, String bindingId, String turnId,
                                    double score, String source) {
        if (score < 0d || score > 1d) {
            throw new IllegalArgumentException("胜任度分必须落在 [0,1]: " + score);
        }
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalArgumentException("胜任度打分必须归属 Turn");
        }
        if (repo.hasCompetence(turnId, bindingId)) {
            return false;
        }
        ModelCompetenceDO row = new ModelCompetenceDO();
        row.setId(Ids.newId("cmp"));
        row.setWorkspaceId(workspaceId);
        row.setBindingId(bindingId);
        row.setTurnId(turnId);
        row.setScore(score);
        row.setSource(source);
        repo.insertCompetence(row);
        return true;
    }

    /** binding 最近一次胜任度分（下一次决策的 previousCompetence 输入）。 */
    public Optional<Double> latestCompetenceScore(String bindingId) {
        return repo.latestCompetenceScore(bindingId);
    }

    public record RouteEvent(String id, String kind, String mode, String decidedGroup,
                             String selectedBindingId, String reason, Integer latencyMs) {}
}
