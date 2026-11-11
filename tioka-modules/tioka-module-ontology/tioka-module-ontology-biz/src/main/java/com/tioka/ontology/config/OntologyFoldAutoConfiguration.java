package com.tioka.ontology.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.ontology.application.def.DefinitionService;
import com.tioka.ontology.application.fold.FoldLock;
import com.tioka.ontology.application.fold.FoldScheduler;
import com.tioka.ontology.application.pack.DomainPackLoader;
import com.tioka.ontology.application.pack.PureFunctionExecutor;
import com.tioka.ontology.infrastructure.OntInstanceVersionRepository;
import com.tioka.ontology.infrastructure.OntStateRepository;
import com.tioka.ontology.infrastructure.RedisFoldLock;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * ONT-07 折叠调度装配。默认关闭：{@code tioka.ontology.fold.enabled=true} 才注册。
 * 注册 FoldScheduler（IdempotentConsumer，经 infra 投递适配器收集）+ OntStateRepository
 * （V173 ont_state）+ Redis 折叠锁（ont:fold:{storeKey}，60s 租约）。
 * PureFunctionExecutor 与领域包 DomainPackLoader 来自 OntologyPackAutoConfiguration
 * （tioka.ontology.pack.enabled）；缺 PureFunctionExecutor 时本装配补默认实例。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "tioka.ontology.fold", name = "enabled", havingValue = "true")
@MapperScan("com.tioka.ontology.infrastructure.mapper")
public class OntologyFoldAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OntStateRepository ontStateRepository(com.tioka.ontology.infrastructure.mapper.OntStateMapper mapper) {
        return new OntStateRepository(mapper);
    }

    @Bean
    @ConditionalOnMissingBean(PureFunctionExecutor.class)
    PureFunctionExecutor foldPureFunctionExecutor() {
        return new PureFunctionExecutor(30);
    }

    @Bean
    @ConditionalOnMissingBean(FoldLock.class)
    @ConditionalOnBean(StringRedisTemplate.class)
    FoldLock redisFoldLock(StringRedisTemplate redis) {
        return new RedisFoldLock(redis);
    }

    @Bean
    @ConditionalOnMissingBean
    FoldScheduler foldScheduler(ObjectMapper mapper,
                                OntInstanceVersionRepository versions,
                                OntStateRepository states,
                                ObjectProvider<FoldLock> lock,
                                ObjectProvider<DomainPackLoader> packLoader,
                                ObjectProvider<DefinitionService> definitions,
                                PureFunctionExecutor executor,
                                ObjectProvider<EventPublisher> publisher) {
        FoldLock foldLock = lock.getIfAvailable();
        if (foldLock == null) {
            throw new IllegalStateException("FoldScheduler 需要 FoldLock（Redis 未装配时请提供内存实现）");
        }
        EventPublisher eventPublisher = publisher.getIfAvailable();
        if (eventPublisher == null) {
            throw new IllegalStateException("FoldScheduler 需要 EventPublisher（ORC-02 outbox）");
        }
        return new FoldScheduler(mapper, versions, states, foldLock, packLoader, definitions,
                executor, eventPublisher);
    }
}
