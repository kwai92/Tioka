package com.tioka.model.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.model.api.CostQueryApi;
import com.tioka.model.infrastructure.ModelUsageRepository;
import com.tioka.model.infrastructure.persistence.ModelUsageDO;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * GOV-05：基于 {@code model_usage} 的成本汇总 / 趋势 / 会话成本。
 */
@Service
public class CostQueryService implements CostQueryApi {
    private final ModelUsageRepository usages;

    public CostQueryService(ModelUsageRepository usages) {
        this.usages = usages;
    }

    @Override
    public List<CostBucket> summary(String groupBy, Instant from, Instant to, String workspaceFilter) {
        String g = requireGroupBy(groupBy);
        List<Map<String, Object>> rows = switch (g) {
            case "workspace" -> usages.summaryByWorkspace(workspaceFilter, from, to);
            case "agent" -> usages.summaryByAgent(workspaceFilter, from, to);
            case "user" -> usages.summaryByUser(workspaceFilter, from, to);
            case "model" -> usages.summaryByModel(workspaceFilter, from, to);
            default -> throw new BizException(ErrorCode.BAD_REQUEST, "groupBy 不支持: " + groupBy);
        };
        return rows.stream().map(this::toBucket).toList();
    }

    @Override
    public List<CostPoint> trend(String scopeType, String scopeId, Instant from, Instant to,
                                 String interval, String workspaceFilter) {
        String st = requireGroupBy(scopeType);
        if (scopeId == null || scopeId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "scopeId 必填");
        }
        if (interval != null && !interval.isBlank() && !"day".equalsIgnoreCase(interval)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "interval 仅支持 day");
        }
        return usages.trendByDay(st, scopeId, workspaceFilter, from, to).stream()
                .map(this::toPoint)
                .toList();
    }

    @Override
    public CostBucket sessionCost(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "threadId 必填");
        }
        Map<String, Object> row = usages.sessionAggregate(threadId);
        if (row == null) {
            return new CostBucket(threadId, threadId, 0, 0, 0, 0);
        }
        return new CostBucket(
                threadId,
                threadId,
                asLong(row.get("promptTokens")),
                asLong(row.get("completionTokens")),
                asLong(row.get("costMicros")),
                asLong(row.get("calls")));
    }

    @Override
    public List<UsageEntry> sessionTimeline(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return List.of();
        }
        return usages.findBySessionId(threadId).stream()
                .map(this::toEntry)
                .toList();
    }

    @Override
    public List<UsageEntry> turnUsage(String turnId) {
        if (turnId == null || turnId.isBlank()) {
            return List.of();
        }
        return usages.findByTurnId(turnId).stream()
                .map(this::toEntry)
                .toList();
    }

    private UsageEntry toEntry(ModelUsageDO row) {
        return new UsageEntry(
                row.getTurnId(),
                row.getStep(),
                row.getModelRef(),
                row.getPromptTokens() == null ? 0 : row.getPromptTokens(),
                row.getCompletionTokens() == null ? 0 : row.getCompletionTokens(),
                row.getCostMicros(),
                row.getLatencyMs(),
                row.getStatus());
    }

    private CostBucket toBucket(Map<String, Object> row) {
        String key = row.get("key") == null ? "" : String.valueOf(row.get("key"));
        String name = row.get("name") == null || String.valueOf(row.get("name")).isBlank()
                ? key : String.valueOf(row.get("name"));
        return new CostBucket(
                key,
                name,
                asLong(row.get("promptTokens")),
                asLong(row.get("completionTokens")),
                asLong(row.get("costMicros")),
                asLong(row.get("calls")));
    }

    private CostPoint toPoint(Map<String, Object> row) {
        String day = row.get("day") == null ? "" : String.valueOf(row.get("day"));
        return new CostPoint(
                day,
                asLong(row.get("promptTokens")),
                asLong(row.get("completionTokens")),
                asLong(row.get("costMicros")),
                asLong(row.get("calls")));
    }

    private static String requireGroupBy(String groupBy) {
        if (groupBy == null || groupBy.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "groupBy/scopeType 必填");
        }
        return switch (groupBy.trim().toLowerCase()) {
            case "workspace", "agent", "user", "model" -> groupBy.trim().toLowerCase();
            default -> throw new BizException(ErrorCode.BAD_REQUEST,
                    "groupBy/scopeType 仅支持 workspace|agent|user|model");
        };
    }

    private static long asLong(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(v));
    }
}
