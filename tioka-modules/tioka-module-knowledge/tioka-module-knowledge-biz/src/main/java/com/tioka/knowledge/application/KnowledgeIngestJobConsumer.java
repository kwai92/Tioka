package com.tioka.knowledge.application;

import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.messaging.IdempotentConsumer;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.knowledge.api.mq.KnowledgeMqTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

/**
 * KNW-05：入库任务触发消费者。事件只带 jobId，阶段推进由 {@link KnowledgeIngestJobService} 完成。
 * 仅在 {@code tioka.knowledge.ingest.async.enabled=true} 时经
 * {@code KnowledgeIngestAsyncAutoConfiguration} 注册。
 */
public class KnowledgeIngestJobConsumer extends IdempotentConsumer<Map<String, Object>> {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestJobConsumer.class);

    private final ObjectProvider<KnowledgeIngestJobService> jobs;
    private final PrincipalHolder principals;

    public KnowledgeIngestJobConsumer(ObjectProvider<KnowledgeIngestJobService> jobs,
                                      PrincipalHolder principals) {
        this.jobs = jobs;
        this.principals = principals;
    }

    @Override
    protected String consumerName() {
        return "knowledge-ingest-job";
    }

    @Override
    protected Class<Map<String, Object>> payloadType() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<Map<String, Object>> type = (Class) Map.class;
        return type;
    }

    @Override
    protected String topic() {
        return KnowledgeMqTopics.TOPIC;
    }

    @Override
    protected String tagExpression() {
        return KnowledgeMqTopics.INGEST_REQUESTED;
    }

    @Override
    protected void handle(EventEnvelope envelope, Map<String, Object> payload) {
        String jobId = payload == null ? null : String.valueOf(payload.get("jobId"));
        if (jobId == null || jobId.isBlank() || "null".equals(jobId)) {
            log.warn("ingest_requested 缺少 jobId，跳过");
            return;
        }
        KnowledgeIngestJobService service = jobs.getIfAvailable();
        if (service == null) {
            log.warn("入库任务服务未装配（async 开关关闭），跳过 jobId={}", jobId);
            return;
        }
        principals.runAs(SystemPrincipals.system(), () -> {
            boolean active = service.process(jobId);
            if (!active) {
                log.debug("ingest job {} 已终止（DONE/FAILED/不存在）", jobId);
            }
        });
    }
}
