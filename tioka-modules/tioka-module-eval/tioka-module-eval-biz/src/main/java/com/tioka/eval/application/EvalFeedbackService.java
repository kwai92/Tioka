package com.tioka.eval.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.eval.api.EvalFeedbackApi;
import com.tioka.eval.api.mq.EvalMqTopics;
import com.tioka.eval.infrastructure.EvalFeedbackRepository;
import com.tioka.eval.infrastructure.persistence.EvalFeedbackDO;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.messaging.IdempotentConsumer;
import com.tioka.governance.api.mq.GovernanceMqTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EVO-01：用户反馈与撤销/法务驳回负向反馈（M3-06 §4.1 / ADR-031）。
 *
 * <p>写入 {@code eval_feedback}（UNIQUE turn_id+user_id，幂等保留人工反馈），并在同一事务
 * 发 outbox {@code tioka.eval.feedback_recorded.v1}。撤销（TRU-07）与法务驳回
 * （{@code tioka.lease.review.feedback.v1}）经事件解耦进入同表，source 区分来源
 * （ADR-071 反馈分诊口径预留）。
 */
public class EvalFeedbackService implements EvalFeedbackApi {

    private static final Logger log = LoggerFactory.getLogger(EvalFeedbackService.class);

    public static final String SOURCE_USER = "USER";
    public static final String SOURCE_UNDO = "UNDO";
    public static final String SOURCE_REVIEW = "REVIEW";

    private final EvalFeedbackRepository repo;
    private final EventPublisher publisher;

    public EvalFeedbackService(EvalFeedbackRepository repo, EventPublisher publisher) {
        this.repo = repo;
        this.publisher = publisher;
    }

    @Override
    public void feedback(String turnId, String userId, int rating, String comment) {
        record(turnId, userId, rating, comment, SOURCE_USER);
    }

    /** 统一入口：rating 仅 +1/-1；UNIQUE 冲突时保留既有反馈。 */
    public boolean record(String turnId, String userId, int rating, String comment, String source) {
        if (turnId == null || turnId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "turnId 不能为空");
        }
        if (rating != 1 && rating != -1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "rating 须为 +1/-1");
        }
        EvalFeedbackDO row = new EvalFeedbackDO();
        row.setId(Ids.newId("fb"));
        row.setTurnId(turnId);
        row.setUserId(userId == null || userId.isBlank() ? "system" : userId);
        row.setRating(rating);
        row.setComment(comment);
        row.setSource(source == null ? SOURCE_USER : source);
        boolean inserted = repo.insertIfAbsent(row);
        if (!inserted) {
            log.debug("反馈已存在，保留原反馈 turnId={} userId={}", turnId, row.getUserId());
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("turnId", row.getTurnId());
        payload.put("userId", row.getUserId());
        payload.put("rating", row.getRating());
        payload.put("source", row.getSource());
        publisher.publish(new DomainEvent(
                EvalMqTopics.EVAL_FEEDBACK_RECORDED,
                EvalMqTopics.TOPIC,
                row.getTurnId(),
                payload,
                0,
                null,
                "turn:" + row.getTurnId()));
        return true;
    }

    /** 撤销自动负向反馈消费者（{@code tioka.trust.undo_recorded.v1}，scopeType=turn 时写入）。 */
    public static class UndoFeedbackConsumer extends IdempotentConsumer<Map<String, Object>> {
        private final EvalFeedbackService service;

        public UndoFeedbackConsumer(EvalFeedbackService service) {
            this.service = service;
        }

        @Override
        protected String consumerName() {
            return "eval-undo-feedback";
        }

        @Override
        protected Class<Map<String, Object>> payloadType() {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<Map<String, Object>> type = (Class) Map.class;
            return type;
        }

        @Override
        protected String topic() {
            return GovernanceMqTopics.TOPIC;
        }

        @Override
        protected String tagExpression() {
            return GovernanceMqTopics.TRUST_UNDO_RECORDED;
        }

        @Override
        protected void handle(EventEnvelope envelope, Map<String, Object> payload) {
            if (payload == null || !"turn".equalsIgnoreCase(String.valueOf(payload.get("scopeType")))) {
                return; // run/action 范围撤销不映射到单条 Turn 反馈
            }
            String turnId = String.valueOf(payload.get("scopeId"));
            String requester = String.valueOf(payload.getOrDefault("requester", "system"));
            service.record(turnId, requester, -1, "撤销自动负向反馈（TRU-07）", SOURCE_UNDO);
        }
    }

    /** 法务驳回负向反馈消费者（EVO-01 预留；产生方 LSE-05 未开工）。 */
    public static class LeaseReviewFeedbackConsumer extends IdempotentConsumer<Map<String, Object>> {
        private static final Logger log = LoggerFactory.getLogger(LeaseReviewFeedbackConsumer.class);

        private final EvalFeedbackService service;
        private final ObjectMapper mapper;

        public LeaseReviewFeedbackConsumer(EvalFeedbackService service, ObjectMapper mapper) {
            this.service = service;
            this.mapper = mapper;
        }

        @Override
        protected String consumerName() {
            return "eval-lease-review-feedback";
        }

        @Override
        protected Class<Map<String, Object>> payloadType() {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<Map<String, Object>> type = (Class) Map.class;
            return type;
        }

        @Override
        protected String topic() {
            return GovernanceMqTopics.TOPIC;
        }

        @Override
        protected String tagExpression() {
            return EvalMqTopics.LEASE_REVIEW_FEEDBACK;
        }

        @Override
        protected void handle(EventEnvelope envelope, Map<String, Object> payload) {
            String turnId = payload == null ? null
                    : (payload.get("turnId") == null ? null : String.valueOf(payload.get("turnId")));
            if (turnId == null || turnId.isBlank() || "null".equals(turnId)) {
                log.warn("lease.review.feedback 缺少 turnId，跳过");
                return;
            }
            String userId = payload.get("userId") == null ? "system" : String.valueOf(payload.get("userId"));
            String comment = payload.get("comment") == null ? "法务驳回负向反馈"
                    : String.valueOf(payload.get("comment"));
            service.record(turnId, userId, -1, comment, SOURCE_REVIEW);
        }
    }
}
