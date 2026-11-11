package com.tioka.eval.api;

import java.time.Instant;
import java.util.List;

/**
 * 轨迹导出端口（EVO-01 / M3-06 §4.1）。复用 HCI-07b TrajectoryApi 查询，导出 JSON
 * 按 OTel GenAI 语义约定命名 span 属性（ADR-029 第 67 条，导出层映射，不写入核心表）。
 * CONFIDENTIAL 及以上数据标签需要空间管理员权限（复用 DAT-02 数据标签机制）。
 */
public interface TrajectoryExportApi {

    /** @return 导出 JSON（tioka.trajectory.export/v1） */
    String export(ExportRequest request);

    /**
     * @param threadId 会话（turnIds 模式下可空，由 Turn 反查）
     * @param turnIds  按 Turn 导出（优先）
     * @param from/to  时间范围（按 Turn startedAt 过滤，可空）
     */
    record ExportRequest(String threadId, List<String> turnIds, Instant from, Instant to) {
    }
}
