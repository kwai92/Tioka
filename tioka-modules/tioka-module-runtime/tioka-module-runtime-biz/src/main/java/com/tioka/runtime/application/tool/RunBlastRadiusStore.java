package com.tioka.runtime.application.tool;

/**
 * TRU-08 单 Run 爆炸半径累计（M3-05 §6）。
 *
 * <p>维度 = （编排 Run 或 Turn、动作类别）。C-21 口径：Runtime 层的 {@code ToolCallContext}
 * 没有 orchRunId（M1 契约），生产 {@code trust_action.orch_run_id} 亦无写入点，故累计以
 * {@code turnId} 为 Run 维度（规格原文「编排 Run 或 Turn」允许）；撤销范围相应使用
 * {@code scope=turn}。改动 M1 契约打通 orch_run_id 留作后续批次，登记偏离。
 *
 * <p>键在 Run 结束后由 TTL 自然过期（本批 2h，覆盖最长 Turn），不依赖显式清理事件。
 */
public interface RunBlastRadiusStore {

    /** 累加本次变更单，返回该 Run 的累计值；Redis 不可用时 unknown=true（宁可多转审批）。 */
    Usage add(String scopeId, String actionClass, long amountMicros, long records);

    /** @param amountMicros 累计金额（micros） @param records 累计记录条数 @param unknown Redis 不可用 */
    record Usage(long amountMicros, long records, boolean unknown) {
        public static Usage unknownUsage() {
            return new Usage(0, 0, true);
        }
    }
}
