package com.tioka.model.api;

/**
 * 路由故障接力事件（runtime 观测侧消费）。
 *
 * @param modelRef     切到的模型（to）
 * @param fromModelRef 切前的模型；可空（手工 failover 兼容）
 * @param turnId       可空
 */
public record FailoverEvent(String sessionId, String modelRef, String reason,
                            String turnId, String fromModelRef) {
    public FailoverEvent(String sessionId, String modelRef, String reason) {
        this(sessionId, modelRef, reason, null, null);
    }
}
