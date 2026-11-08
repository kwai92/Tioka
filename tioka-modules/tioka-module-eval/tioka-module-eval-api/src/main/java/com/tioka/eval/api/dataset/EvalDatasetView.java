package com.tioka.eval.api.dataset;

/**
 * 评测数据集摘要（EVO-02）。
 */
public record EvalDatasetView(
        String datasetId,
        String workspaceId,
        String name,
        int latestVersion
) {}
