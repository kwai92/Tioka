package com.tioka.runtime.kernel.infrastructure;

/**
 * Runtime Item 写入命令（48-CS：业务入参收敛为对象）。
 *
 * @param leaseVersion {@code null} 表示不校验租约（QUEUED 写用户消息等）
 * @param seq          {@code null} 表示分配新 seq；非空表示写入预留 seq
 * @param dataLabel    DAT-02：工具结果数据标签（仅 TOOL_RESULT 写入点携带）；null = 未打标
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
        String dataLabel
) {
    /** 兼容 9 参口径（无标签）。 */
    public ItemWrite(String threadId, String turnId, Long leaseVersion, Long seq,
                     int step, String type, String role, String contentJson, Integer tokenCount) {
        this(threadId, turnId, leaseVersion, seq, step, type, role, contentJson, tokenCount, null);
    }

    public static ItemWrite append(String threadId, String turnId, long leaseVersion,
                                   int step, String type, String role, String contentJson, Integer tokenCount) {
        return new ItemWrite(threadId, turnId, leaseVersion, null, step, type, role, contentJson, tokenCount, null);
    }

    /** DAT-02：带数据标签的工具结果写入。 */
    public static ItemWrite appendLabeled(String threadId, String turnId, long leaseVersion,
                                          int step, String type, String role, String contentJson,
                                          Integer tokenCount, String dataLabel) {
        return new ItemWrite(threadId, turnId, leaseVersion, null, step, type, role,
                contentJson, tokenCount, dataLabel);
    }

    public static ItemWrite withoutLease(String threadId, String turnId,
                                         int step, String type, String role, String contentJson) {
        return new ItemWrite(threadId, turnId, null, null, step, type, role, contentJson, null, null);
    }

    public static ItemWrite reserved(long seq, String threadId, String turnId, long leaseVersion,
                                     int step, String type, String role, String contentJson, Integer tokenCount) {
        return new ItemWrite(threadId, turnId, leaseVersion, seq, step, type, role, contentJson, tokenCount, null);
    }
}
