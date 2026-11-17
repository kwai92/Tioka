package com.tioka.governance.api.manifest;

import java.time.Instant;

/**
 * 运行清单视图（OBS-06 / ADR-075 §3）：供时间线（OBS-08）展开与同类运行差异对比。
 *
 * @param manifestJson ADR-075 §3 全量内容（版本集合等）的 JSON 文本
 * @param contentHash  清单内容规范化哈希；两条同类运行 {@code contentHash} 不同即「清单有差异」
 *                     （回答「为什么昨天放行、今天拦截」）
 */
public record RunManifestView(
        String manifestId,
        String runKind,
        String runId,
        int seq,
        String requestId,
        String workspaceId,
        String subject,
        String traceId,
        String agentVersionId,
        String specHash,
        String grantId,
        String manifestJson,
        String contentHash,
        Instant createdAt
) {}
