package com.tioka.ontology.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.ontology.application.def.DefinitionService;
import com.tioka.ontology.application.schema.SchemaRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 默认关闭：{@code tioka.ontology.definition.enabled=true} 才注册 DefinitionService。
 * 不改 application.yml；未设置属性时不装配，不改变现有行为。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "tioka.ontology.definition", name = "enabled", havingValue = "true")
public class OntologyDefinitionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SchemaRegistry.class)
    SchemaRegistry schemaRegistryForDefinition(ObjectMapper objectMapper) {
        return new SchemaRegistry(objectMapper);
    }

    @Bean
    DefinitionService definitionService(ObjectMapper objectMapper, SchemaRegistry schemaRegistry) {
        return new DefinitionService(objectMapper, schemaRegistry);
    }
}
