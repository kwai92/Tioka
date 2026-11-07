package com.tioka.runtime.application;

import com.tioka.model.api.CostQueryApi;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * MH-05：会话成本时间线改查 {@code model_usage}（V121 删除 {@code cost_event}）。
 * 路径 {@code GET /api/runtime/cost/sessions/{id}} 不变。
 */
@Service
public class CostTimelineService {
    private final CostQueryApi costs;

    public CostTimelineService(CostQueryApi costs) {
        this.costs = costs;
    }

    public List<CostEvent> timeline(String sessionId) {
        return costs.sessionTimeline(sessionId).stream()
                .map(u -> new CostEvent(
                        u.turnId(),
                        u.step(),
                        u.modelRef(),
                        u.promptTokens(),
                        u.completionTokens(),
                        u.costMicros(),
                        u.latencyMs(),
                        u.status()))
                .toList();
    }

    /** M1-03 MH-05：字段对齐 model_usage；step 在 V143 前恒为 null。 */
    public record CostEvent(
            String turnId,
            Integer step,
            String modelRef,
            int promptTokens,
            int completionTokens,
            Long costMicros,
            Integer latencyMs,
            String status) {}
}
