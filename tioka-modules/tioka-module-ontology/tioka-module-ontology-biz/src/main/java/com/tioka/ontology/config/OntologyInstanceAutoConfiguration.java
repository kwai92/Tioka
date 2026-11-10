package com.tioka.ontology.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.ontology.application.def.DefinitionService;
import com.tioka.ontology.application.instance.ConfirmIngestor;
import com.tioka.ontology.application.instance.DiffService;
import com.tioka.ontology.application.instance.DraftProposer;
import com.tioka.ontology.application.instance.DraftService;
import com.tioka.ontology.application.schema.SchemaRegistry;
import com.tioka.ontology.infrastructure.OntDraftRepository;
import com.tioka.ontology.infrastructure.OntInstanceVersionRepository;
import com.tioka.ontology.infrastructure.OntValueIndexRepository;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * ONT-02 实例投影装配。默认关闭：{@code tioka.ontology.instance.enabled=true} 才注册。
 * 持久化经 MyBatis（V172 三表）；ConfirmedModelClient / DraftProposer 随 CON-07a 接线
 * （ObjectProvider 弱依赖：缺省时确认消费抛错重试 → 死信）。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "tioka.ontology.instance", name = "enabled", havingValue = "true")
@MapperScan("com.tioka.ontology.infrastructure.mapper")
public class OntologyInstanceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    DraftService draftService(OntDraftRepository drafts,
                              ObjectProvider<DraftProposer> proposer,
                              ObjectMapper mapper) {
        return new DraftService(drafts, proposer, mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    DiffService diffService() {
        return new DiffService();
    }

    @Bean
    @ConditionalOnBean(EventPublisher.class)
    @ConditionalOnMissingBean
    ConfirmIngestor confirmIngestor(ObjectMapper mapper,
                                    OntInstanceVersionRepository versions,
                                    OntDraftRepository drafts,
                                    OntValueIndexRepository valueIndex,
                                    ObjectProvider<com.tioka.ontology.application.instance.ConfirmedModelClient> modelClient,
                                    ObjectProvider<SchemaRegistry> schemaRegistry,
                                    ObjectProvider<DefinitionService> definitions,
                                    EventPublisher publisher) {
        return new ConfirmIngestor(mapper, versions, drafts, valueIndex, modelClient,
                schemaRegistry, definitions, publisher);
    }
}
