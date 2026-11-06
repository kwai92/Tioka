package com.tioka.runtime.application;

/** Turn 结束后的用量事件（原 RuntimeSessionService 内嵌事件）。 */
public record UsageReportedEvent(
        String sessionId,
        String modelRef,
        int promptTokens,
        int completionTokens,
        String routeReason) {}
