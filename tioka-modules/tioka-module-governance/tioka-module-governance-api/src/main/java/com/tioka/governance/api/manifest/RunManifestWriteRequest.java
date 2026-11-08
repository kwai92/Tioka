package com.tioka.governance.api.manifest;

import java.util.Map;

/**
 * 运行清单写入请求（ADR-075 / OBS-06）。
 */
public record RunManifestWriteRequest(
        String runKind,
        String runId,
        String requestId,
        String workspaceId,
        String agentVersionId,
        String grantId,
        String specHash,
        Map<String, String> versions
) {}
