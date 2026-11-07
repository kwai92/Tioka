package com.tioka.system.api.spi;

import java.time.Instant;

/**
 * 配额卡点（GOV-03 / ADR-017）：ModelGateway 预检与记账、TurnRunner 并发占位。
 */
public interface QuotaGuard {

    /**
     * 预检本月 token：已用 &lt; 上限才放行；未配置上限 = 不限。
     * 不估算本次用量（ADR-029 第 7 条）。
     */
    void precheckTokens(String workspaceId, String userId, String agentId);

    /** 按实际 token 累加 Redis 计数（允许单次超出上限）。 */
    void consumeTokens(String workspaceId, String userId, String agentId, long tokens);

    /**
     * 并发 Turn 占位（workspace + user 任一失败则回滚）。
     *
     * @param expiresAt 租约过期时间（ZSet score）
     * @return true 占位成功
     */
    boolean tryAcquireConcurrent(String workspaceId, String userId, String turnId, Instant expiresAt);

    /** 续租时更新 score。 */
    void renewConcurrent(String workspaceId, String userId, String turnId, Instant expiresAt);

    /** Turn 终态或进入 WAITING_* 时释放。 */
    void releaseConcurrent(String workspaceId, String userId, String turnId);
}
