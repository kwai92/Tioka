package com.tioka.orchestration.api.engine;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 编排入口（ORC-03，ADR-020）。规则只看 {@link EventEnvelope#attrs()}，不读 body。
 */
public interface OrchApi {

    /** 按 {@code (workspaceId, idempotencyKey)} 幂等（库表唯一键见 {@code orch_event.idempotency_key}）。 */
    EventAck ingest(EventEnvelope event);

    RunView getRun(String runId);

    void cancelRun(String runId, String reason);

    /** why 时间线，按 seq 升序。 */
    List<FactView> facts(String runId, long afterSeq);

    enum Disposition { STORED, DUPLICATE, FILTERED }

    enum Strength { EXPLICIT, INFERRED }

    enum RunStatus { PENDING, DISPATCHED, RUNNING, WAITING_ATTENTION, UNCERTAIN, SUCCEEDED, FAILED, CANCELLED }

    /**
     * @param source         来源，如 feishu、timer、orch、webhook:{system}
     * @param type           事件类型，规则按此匹配
     * @param idempotencyKey 来源 + 外部 ID
     * @param subjectKey     归单键
     * @param attrs          可信属性；约定键：{@code tioka.addressed}、{@code tioka.actor_is_bot}、{@code tioka.external_thread}
     * @param body           原始内容，只传给 Agent 作输入
     * @param hops           派生深度，超过上限即丢弃（ORC-07）
     */
    record EventEnvelope(String source, String type, String idempotencyKey, String subjectKey,
                         Map<String, String> attrs, Map<String, Object> body, int hops) {
        public EventEnvelope {
            attrs = attrs == null ? Map.of() : Map.copyOf(attrs);
            body = body == null ? Map.of() : body;
        }
    }

    record EventAck(String eventId, Disposition disposition) {
        public static EventAck stored(String eventId) {
            return new EventAck(eventId, Disposition.STORED);
        }

        public static EventAck duplicate(String eventId) {
            return new EventAck(eventId, Disposition.DUPLICATE);
        }

        public static EventAck filtered(String eventId) {
            return new EventAck(eventId, Disposition.FILTERED);
        }
    }

    record RunView(String runId, String workItemId, String ruleId, String ruleVersionId, RunStatus status,
                   int attempt, String threadId, String currentTurnId, Map<String, Object> receipt,
                   String errorCode, String errorMessage, Instant createdAt, Instant endedAt) {}

    record FactView(long seq, String kind, Map<String, Object> detail, Instant at) {}
}
