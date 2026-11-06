package com.tioka.model.api;

import java.time.Instant;
import java.util.List;

/**
 * 成本归集查询（GOV-05）。数据源为 {@code model_usage}（V102；由 MH-05 写入）。
 */
public interface CostQueryApi {

    /**
     * @param groupBy          workspace | agent | user | model
     * @param workspaceFilter  非空时仅汇总该空间；系统管理员可传 null 看全部
     */
    List<CostBucket> summary(String groupBy, Instant from, Instant to, String workspaceFilter);

    /**
     * @param scopeType        workspace | agent | user | model
     * @param interval         目前仅支持 day
     * @param workspaceFilter  非空时额外按空间过滤（非管理员强制当前空间）
     */
    List<CostPoint> trend(String scopeType, String scopeId, Instant from, Instant to,
                          String interval, String workspaceFilter);

    /** 按 runtime session / thread 汇总单会话成本。 */
    CostBucket sessionCost(String sessionId);

    record CostBucket(
            String key,
            String name,
            long promptTokens,
            long completionTokens,
            long costMicros,
            long calls) {}

    record CostPoint(
            String day,
            long promptTokens,
            long completionTokens,
            long costMicros,
            long calls) {}
}
