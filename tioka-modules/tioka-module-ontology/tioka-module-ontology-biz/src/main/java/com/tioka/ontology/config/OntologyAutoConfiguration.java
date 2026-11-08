package com.tioka.ontology.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.ontology.api.schema.SchemaApi;
import com.tioka.ontology.application.schema.SchemaRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 默认关闭：{@code tioka.ontology.schema.enabled=true} 才注册 SchemaApi，不改变 M2 行为。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "tioka.ontology.schema", name = "enabled", havingValue = "true")
public class OntologyAutoConfiguration {

    @Bean
    SchemaApi schemaApi(ObjectMapper objectMapper) {
        return new SchemaRegistry(objectMapper);
    }
}
