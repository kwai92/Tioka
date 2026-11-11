package com.tioka.eval.application;

import com.tioka.eval.infrastructure.EvalFeedbackRepository;
import com.tioka.eval.infrastructure.persistence.EvalFeedbackDO;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.governance.api.mq.GovernanceMqTopics;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EVO-01 反馈验收（M3-06 §4.1）：点踩写入；TRU-07 撤销自动写负向反馈；
 * {@code lease.review.feedback} 消费路径预留可用。离线内存替身（同 UndoIT 做法）。
 *
 * <p>撤销端发布 {@code tioka.trust.undo_recorded.v1} 由 runtime-biz UndoIT 断言，
 * 本端按事件契约消费——两端以契约 schema 对齐，依赖方向经事件解耦。
 */
class FeedbackIT {

    /** 内存反馈存储（UNIQUE turn_id+user_id 幂等语义）。 */
    static final class InMemoryFeedbackRepo extends EvalFeedbackRepository {
        final Map<String, EvalFeedbackDO> rows = new LinkedHashMap<>();

        InMemoryFeedbackRepo() {
            super(null);
        }

        @Override
        public boolean insertIfAbsent(EvalFeedbackDO row) {
            String key = row.getTurnId() + "|" + row.getUserId();
            if (rows.containsKey(key)) {
                return false;
            }
            rows.put(key, row);
            return true;
        }

        @Override
        public EvalFeedbackDO find(String turnId, String userId) {
            return rows.get(turnId + "|" + userId);
        }
    }

    private final InMemoryFeedbackRepo repo = new InMemoryFeedbackRepo();
    private final List<DomainEvent> events = new CopyOnWriteArrayList<>();
    private final EvalFeedbackService service = new EvalFeedbackService(repo, events::add);

    @Test
    void thumbDownWritesFeedback() {
        service.feedback("turn_1", "u1", -1, "回答与引用不符");

        EvalFeedbackDO row = repo.find("turn_1", "u1");
        assertNotNull(row);
        assertEquals(-1, row.getRating());
        assertEquals("回答与引用不符", row.getComment());
        assertEquals(EvalFeedbackService.SOURCE_USER, row.getSource());
        // 反馈记录事件（outbox）
        assertEquals(1, events.stream()
                .filter(e -> "tioka.eval.feedback_recorded.v1".equals(e.event())).count());

        // 同 (turn,user) 重复反馈：UNIQUE 幂等，保留原反馈
        assertFalse(service.record("turn_1", "u1", 1, "改主意了", EvalFeedbackService.SOURCE_USER));
        assertEquals(-1, repo.find("turn_1", "u1").getRating());

        // 其他用户同 Turn 可点赞
        assertTrue(service.record("turn_1", "u2", 1, null, EvalFeedbackService.SOURCE_USER));
        assertEquals(1, repo.find("turn_1", "u2").getRating());
    }

    @Test
    void undoWritesNegativeFeedback() {
        UndoFeedbackEnvelope envelope = envelope(GovernanceMqTopics.TRUST_UNDO_RECORDED, undoPayload(
                "turn_9", "turn", "u_undo"));
        new EvalFeedbackService.UndoFeedbackConsumer(service).dispatch(
                envelope.envelope(), envelope.payload());

        EvalFeedbackDO row = repo.find("turn_9", "u_undo");
        assertNotNull(row, "turn 范围撤销应自动写负向反馈");
        assertEquals(-1, row.getRating());
        assertEquals(EvalFeedbackService.SOURCE_UNDO, row.getSource());

        // run/action 范围撤销不映射单条 Turn 反馈
        new EvalFeedbackService.UndoFeedbackConsumer(service).dispatch(
                envelope(GovernanceMqTopics.TRUST_UNDO_RECORDED, undoPayload(
                        "run_1", "run", "u_undo")).envelope(),
                undoPayload("run_1", "run", "u_undo"));
        assertNull(repo.find("run_1", "u_undo"));
    }

    @Test
    void leaseReviewFeedbackReservedPath() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("turnId", "turn_5");
        payload.put("userId", "legal_1");
        payload.put("comment", "合同要点 12 驳回");
        EventEnvelope env = envelope(GovernanceMqTopics.TOPIC, payload).envelope();
        new EvalFeedbackService.LeaseReviewFeedbackConsumer(service, envelopeMapper()).dispatch(env, payload);

        EvalFeedbackDO row = repo.find("turn_5", "legal_1");
        assertNotNull(row, "法务驳回负向反馈（预留路径）");
        assertEquals(-1, row.getRating());
        assertEquals(EvalFeedbackService.SOURCE_REVIEW, row.getSource());

        // 缺 turnId 容错跳过（LSE-05 未开工，payload 形态以契约为准）
        new EvalFeedbackService.LeaseReviewFeedbackConsumer(service, envelopeMapper()).dispatch(
                envelope(GovernanceMqTopics.TOPIC, Map.of("comment", "no turn")).envelope(),
                Map.of("comment", "no turn"));
        assertEquals(1, repo.rows.size(), "缺 turnId 不应写入");
    }

    // ------------------------------------------------------------------

    private record UndoFeedbackEnvelope(EventEnvelope envelope, Map<String, Object> payload) {}

    private static UndoFeedbackEnvelope envelope(String event, Map<String, Object> payload) {
        EventEnvelope env = new EventEnvelope("evt_test", event, 1, "ws_c13",
                Instant.now().toString(), "runtime-biz", null, null, null, null,
                "test:subject", "subject_key", 0, null, payload);
        return new UndoFeedbackEnvelope(env, payload);
    }

    private static Map<String, Object> undoPayload(String scopeId, String scopeType, String requester) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("undoId", "undo_1");
        payload.put("scopeType", scopeType);
        payload.put("scopeId", scopeId);
        payload.put("requester", requester);
        payload.put("workspaceId", "ws_c13");
        payload.put("compensating", 0);
        payload.put("irreversible", 0);
        return payload;
    }

    private static com.fasterxml.jackson.databind.ObjectMapper envelopeMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper();
    }
}
