package com.tioka.knowledge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.knowledge.application.KnowledgeIngestJobConsumer;
import com.tioka.knowledge.application.KnowledgeIngestJobService;
import com.tioka.knowledge.application.parse.ParserPort;
import com.tioka.knowledge.infrastructure.KnowledgeRepository;
import com.tioka.model.api.ModelBindingQueryApi;
import com.tioka.model.api.gateway.ModelGateway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * KNW-05：入库异步化装配（M3-06 §3.1）。
 * 双开关纪律：仅 {@code tioka.knowledge.ingest.async.enabled=true} 时注册任务服务与消费者；
 * 缺省关闭，KnowledgeService 走既有同步路径（C-8/C-12 收口纪律）。
 * KNW-11：ParserPort 经 ObjectProvider 弱依赖（条件装配缺席 → 本地结构化降级）。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "tioka.knowledge.ingest.async", name = "enabled", havingValue = "true")
public class KnowledgeIngestAsyncAutoConfiguration {

    @Bean
    public KnowledgeIngestJobService knowledgeIngestJobService(KnowledgeRepository repo,
                                                               ModelGateway modelGateway,
                                                               ModelBindingQueryApi bindings,
                                                               PrincipalHolder principals,
                                                               ObjectProvider<ParserPort> parserPort,
                                                               EventPublisher publisher,
                                                               ObjectMapper mapper,
                                                               @Value("${tioka.knowledge.embedding-dim:1024}")
                                                               int embeddingDim,
                                                               PlatformTransactionManager transactionManager) {
        return new KnowledgeIngestJobService(repo, modelGateway, bindings, principals, parserPort,
                publisher, mapper, embeddingDim, transactionManager);
    }

    @Bean
    public KnowledgeIngestJobConsumer knowledgeIngestJobConsumer(
            ObjectProvider<KnowledgeIngestJobService> jobs, PrincipalHolder principals) {
        return new KnowledgeIngestJobConsumer(jobs, principals);
    }
}
