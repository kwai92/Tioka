package com.tioka.model.api;

/** 路由故障接力事件（runtime 观测侧消费）。 */
public record FailoverEvent(String sessionId, String modelRef, String reason) {}
