package com.tioka.eval.api.dataset;

/**
 * 评测数据集摘要（EVO-02；C-18 EVO-07 按 ADR-068 第 1 节加分层与材料单元键路径）。
 */
public record EvalDatasetView(
        String datasetId,
        String workspaceId,
        String name,
        int latestVersion,
        String tier,
        String unitKeyPath
) {

    /** 兼容旧 4 参口径（tier=DEV、unitKeyPath 未声明）。 */
    public EvalDatasetView(String datasetId, String workspaceId, String name, int latestVersion) {
        this(datasetId, workspaceId, name, latestVersion, "DEV", null);
    }
}
