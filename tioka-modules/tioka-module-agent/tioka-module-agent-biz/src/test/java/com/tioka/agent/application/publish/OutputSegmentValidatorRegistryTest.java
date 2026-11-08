package com.tioka.agent.application.publish;

import com.tioka.agent.api.publish.PublishCheck;
import com.tioka.ontology.api.schema.SchemaApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCH-02：output 段校验器经 {@link DefaultAgentSpecValidatorRegistry} 发布。
 */
class OutputSegmentValidatorRegistryTest {

    @Test
    void registryIncludesOutputContractPublishCheck() {
        OutputContractPublishCheck outputCheck = new OutputContractPublishCheck(emptyProvider());
        DefaultAgentSpecValidatorRegistry registry = new DefaultAgentSpecValidatorRegistry(List.of(
                new SchemaPublishCheck(new com.fasterxml.jackson.databind.ObjectMapper()),
                outputCheck));

        List<PublishCheck> validators = registry.validators();
        assertTrue(validators.stream().anyMatch(OutputContractPublishCheck.class::isInstance),
                "OutputContractPublishCheck 须注册到 AgentSpecValidatorRegistry");
    }

    private static ObjectProvider<SchemaApi> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public SchemaApi getObject() {
                return null;
            }

            @Override
            public SchemaApi getObject(Object... args) {
                return null;
            }

            @Override
            public SchemaApi getIfAvailable() {
                return null;
            }

            @Override
            public SchemaApi getIfUnique() {
                return null;
            }
        };
    }
}
