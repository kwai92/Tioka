package com.tioka.orchestration.api;

import java.util.List;
import java.util.Map;

public interface OrchApi {
    String ingestEvent(String eventType, String idempotencyKey, Map<String, Object> payload);
    RunView claim(String workerId);
    void heartbeat(String runId, String workerId);
    AttentionView openAttention(String runId, String title, Map<String, Object> card);
    AttentionView answer(String attentionId, Map<String, Object> answer);
    List<WhyStep> why(String runId);

    record RunView(String id, String status, String eventId, String workerId, long leaseUntil) {}
    record AttentionView(String id, String runId, String status, String title, Map<String, Object> card, Map<String, Object> answer) {}
    record WhyStep(String at, String kind, String detail) {}
}
