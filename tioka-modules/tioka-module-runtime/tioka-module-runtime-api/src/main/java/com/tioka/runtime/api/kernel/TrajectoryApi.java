package com.tioka.runtime.api.kernel;

import java.time.Instant;
import java.util.List;

/**
 * 轨迹查询（HCI-07，施工设计 M2-04）。数据来自 runtime_item（含作废项）与 model_usage。
 */
public interface TrajectoryApi {

    Trajectory turn(String turnId);

    List<TurnSummary> turns(String threadId, int page, int size);

    /** @param traceUrl 由 runtime_turn.traceparent 拼出的 Jaeger 链接，可空 */
    record Trajectory(RuntimeApi.TurnView turn, List<Step> steps, String traceUrl) {}

    record Step(int step, List<RuntimeApi.ItemView> items, String modelRef, Integer promptTokens,
                Integer completionTokens, Long latencyMs, Long costMicros, boolean failover) {}

    record TurnSummary(String turnId, int seq, TurnStatus status, String origin, int stepCount,
                       Integer promptTokens, Integer completionTokens, Long costMicros,
                       Instant startedAt, Instant endedAt) {}
}
