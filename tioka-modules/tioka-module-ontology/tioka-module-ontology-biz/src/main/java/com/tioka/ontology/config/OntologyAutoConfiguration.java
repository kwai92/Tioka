package com.tioka.ontology.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.ontology.api.schema.SchemaApi;
import com.tioka.ontology.application.schema.EvidenceBinder;
import com.tioka.ontology.application.schema.SchemaRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 默认关闭：{@code tioka.ontology.schema.enabled=true} 才注册 SchemaApi，不改变 M2 行为。
 * SCH-03：EvidenceBinder 经 ObjectProvider 弱依赖（tioka.ontology.doc.enabled 独立开关，
 * doc 未启用时 checkEvidence 返回空清单并标明「证据检查跳过」）。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "tioka.ontology.schema", name = "enabled", havingValue = "true")
public class OntologyAutoConfiguration {

    @Bean
    SchemaApi schemaApi(ObjectMapper objectMapper, ObjectProvider<EvidenceBinder> evidenceBinder) {
        return new SchemaRegistry(objectMapper, evidenceBinder);
    }
}
