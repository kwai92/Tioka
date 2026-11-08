package com.tioka.runtime.kernel.infrastructure;

/**
 * Runtime Item 写入命令（48-CS：业务入参收敛为对象）。
 *
 * @param leaseVersion {@code null} 表示不校验租约（QUEUED 写用户消息等）
 * @param seq          {@code null} 表示分配新 seq；非空表示写入预留 seq
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
        Integer tokenCount
) {
    public static ItemWrite append(String threadId, String turnId, long leaseVersion,
                                   int step, String type, String role, String contentJson, Integer tokenCount) {
        return new ItemWrite(threadId, turnId, leaseVersion, null, step, type, role, contentJson, tokenCount);
    }

    public static ItemWrite withoutLease(String threadId, String turnId,
                                         int step, String type, String role, String contentJson) {
        return new ItemWrite(threadId, turnId, null, null, step, type, role, contentJson, null);
    }

    public static ItemWrite reserved(long seq, String threadId, String turnId, long leaseVersion,
                                     int step, String type, String role, String contentJson, Integer tokenCount) {
        return new ItemWrite(threadId, turnId, leaseVersion, seq, step, type, role, contentJson, tokenCount);
    }
}
