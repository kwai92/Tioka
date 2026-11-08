package com.tioka.orchestration.api.engine;

/**
 * 归单结果（供 decide → dispatch 衔接）。
 *
 * @param workItemId       工单 ID
 * @param mandateId        Mandate ID（新建工单时创建；已有工单复用时可为原 Mandate）
 * @param runId            新建的 Run ID；merge 只追加 pending 时为空
 * @param createdWorkItem  是否新建工单
 * @param createdRun       是否新建 Run
 * @param queued           serial 下因已有活动 Run 而排队
 * @param concurrency      实际并发模式
 */
public record AssignResult(
        String workItemId,
        String mandateId,
        String runId,
        boolean createdWorkItem,
        boolean createdRun,
        boolean queued,
        ConcurrencyMode concurrency) {
}
