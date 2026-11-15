package com.tioka.runtime.kernel.infrastructure;

/**
 * Runtime Item 写入命令（48-CS：业务入参收敛为对象）。
 *
 * @param leaseVersion {@code null} 表示不校验租约（QUEUED 写用户消息等）
 * @param seq          {@code null} 表示分配新 seq；非空表示写入预留 seq
 * @param dataLabel    DAT-02：工具结果数据标签（仅 TOOL_RESULT 写入点携带）；null = 未打标
 * @param schemaRef    SCH-07：data item 的权威 Schema 引用（精确到版本）；非 data item 为 null
 * @param outcome      SCH-07：四种结局（VALID / VALID_WITH_UNKNOWN / HUMAN_PENDING / FAILED）；非 data item 为 null
 * @param reasonCode   SCH-07：失败原因码；成功/非 data item 为 null
 * @param modelRef     SCH-10：data item 的产出模型 ref（producedBy.model）；非 data item 为 null
 * @param outputMode   SCH-10：结构化输出提交方式（OutputMode：FINAL / TOOL）；非 data item 为 null
 * @param repairRounds SCH-10：修复轮数（producedBy.repairRounds）；非 data item 为 null
 */
public record ItemWrite(
        String threadId,
        String turnId,
        Long leaseVersion,
        Long seq,
        int step,
        String type,
        String role,
        String contentJson,
        Integer tokenCount,
        String dataLabel,
        String schemaRef,
        String outcome,
        String reasonCode,
        String modelRef,
        String outputMode,
        Integer repairRounds
) {
    /** 兼容 9 参口径（无标签、无结局列）。 */
    public ItemWrite(String threadId, String turnId, Long leaseVersion, Long seq,
                     int step, String type, String role, String contentJson, Integer tokenCount) {
        this(threadId, turnId, leaseVersion, seq, step, type, role, contentJson, tokenCount,
                null, null, null, null, null, null, null);
    }

    public static ItemWrite append(String threadId, String turnId, long leaseVersion,
                                   int step, String type, String role, String contentJson, Integer tokenCount) {
        return new ItemWrite(threadId, turnId, leaseVersion, null, step, type, role, contentJson, tokenCount,
                null, null, null, null, null, null, null);
    }

    /** DAT-02：带数据标签的工具结果写入。 */
    public static ItemWrite appendLabeled(String threadId, String turnId, long leaseVersion,
                                          int step, String type, String role, String contentJson,
                                          Integer tokenCount, String dataLabel) {
        return new ItemWrite(threadId, turnId, leaseVersion, null, step, type, role,
                contentJson, tokenCount, dataLabel, null, null, null, null, null, null);
    }

    /**
     * SCH-07 / SCH-10：data item 写入（结局与维度列为 content_json 的投影，供 §10.1 指标聚合）。
     */
    public static ItemWrite appendData(String threadId, String turnId, long leaseVersion,
                                       int step, String type, String role, String contentJson,
                                       String schemaRef, String outcome, String reasonCode,
                                       String modelRef, String outputMode, Integer repairRounds) {
        return new ItemWrite(threadId, turnId, leaseVersion, null, step, type, role,
                contentJson, null, null, schemaRef, outcome, reasonCode, modelRef, outputMode, repairRounds);
    }

    public static ItemWrite withoutLease(String threadId, String turnId,
                                         int step, String type, String role, String contentJson) {
        return new ItemWrite(threadId, turnId, null, null, step, type, role, contentJson, null,
                null, null, null, null, null, null, null);
    }

    public static ItemWrite reserved(long seq, String threadId, String turnId, long leaseVersion,
                                     int step, String type, String role, String contentJson, Integer tokenCount) {
        return new ItemWrite(threadId, turnId, leaseVersion, seq, step, type, role, contentJson, tokenCount,
                null, null, null, null, null, null, null);
    }
}
