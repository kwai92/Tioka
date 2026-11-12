package com.tioka.agent.application.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.publish.PublishCheck;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.api.spec.OutputContract;
import com.tioka.agent.api.spec.OutputMode;
import com.tioka.ontology.api.schema.SchemaApi;
import com.tioka.ontology.api.schema.SchemaDef;
import com.tioka.ontology.api.schema.SchemaError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputContractPublishCheckTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private SchemaPublishCheck schemaPublishCheck;

    @BeforeEach
    void setUp() {
        schemaPublishCheck = new SchemaPublishCheck(mapper, false, false);
    }

    @Test
    void nullOutputPasses() {
        OutputContractPublishCheck check = new OutputContractPublishCheck(provider(publishedApi()));
        AgentSpec spec = minimalSpec(null);
        assertTrue(check.check(spec, ctx()).isEmpty());
        assertTrue(schemaPublishCheck.check(spec, ctx()).stream()
                .noneMatch(e -> e.path().startsWith("/output")));
    }

    @Test
    void publishedSchemaRefAccepted() {
        OutputContractPublishCheck check = new OutputContractPublishCheck(provider(publishedApi()));
        AgentSpec spec = minimalSpec(new OutputContract("lease.finding@3", OutputMode.FINAL, 2, false, null, null));
        assertTrue(check.check(spec, ctx()).isEmpty());
        assertTrue(schemaPublishCheck.check(spec, ctx()).stream()
                .noneMatch(e -> "RESERVED_FIELD".equals(e.code()) && e.path().equals("/output")));
    }

    @Test
    void missingSchemaRefRejected() {
        OutputContractPublishCheck check = new OutputContractPublishCheck(provider(emptyApi()));
        AgentSpec spec = minimalSpec(new OutputContract("missing@1", OutputMode.FINAL, 2, false, null, null));
        List<PublishCheck.PublishError> errors = check.check(spec, ctx());
        assertEquals(1, errors.size());
        assertEquals("/output/schemaRef", errors.get(0).path());
        assertEquals("REF_NOT_FOUND", errors.get(0).code());
    }

    @Test
    void unpublishedSchemaRefRejected() {
        SchemaApi stub = new SchemaApi() {
            @Override
            public Optional<SchemaDef> get(String ref) {
                return Optional.of(new SchemaDef(
                        "draft.schema", 1, mapper.createObjectNode(), "platform", "INTERNAL", "h", "DRAFT"));
            }

            @Override
            public List<SchemaError> validate(String ref, JsonNode payload) {
                return List.of();
            }

            @Override
            public List<SchemaError> checkEvidence(String ref, JsonNode payload, List<String> visibleDocIds) {
                return List.of();
            }
        };
        OutputContractPublishCheck check = new OutputContractPublishCheck(provider(stub));
        AgentSpec spec = minimalSpec(new OutputContract("draft.schema@1", OutputMode.FINAL, 2, false, null, null));
        List<PublishCheck.PublishError> errors = check.check(spec, ctx());
        assertEquals(1, errors.size());
        assertEquals("REF_UNAVAILABLE", errors.get(0).code());
    }

    private SchemaApi publishedApi() {
        return new SchemaApi() {
            @Override
            public Optional<SchemaDef> get(String ref) {
                if (!"lease.finding@3".equals(ref)) {
                    return Optional.empty();
                }
                return Optional.of(new SchemaDef(
                        "lease.finding", 3, mapper.createObjectNode(), "platform", "INTERNAL", "h", "PUBLISHED"));
            }

            @Override
            public List<SchemaError> validate(String ref, JsonNode payload) {
                return List.of();
            }

            @Override
            public List<SchemaError> checkEvidence(String ref, JsonNode payload, List<String> visibleDocIds) {
                return List.of();
            }
        };
    }

    private static SchemaApi emptyApi() {
        return new SchemaApi() {
            @Override
            public Optional<SchemaDef> get(String ref) {
                return Optional.empty();
            }

            @Override
            public List<SchemaError> validate(String ref, JsonNode payload) {
                return List.of();
            }

            @Override
            public List<SchemaError> checkEvidence(String ref, JsonNode payload, List<String> visibleDocIds) {
                return List.of();
            }
        };
    }

    private static PublishCheck.PublishContext ctx() {
        return new PublishCheck.PublishContext("ag_1", "u_1", false);
    }

    private static ObjectProvider<SchemaApi> provider(SchemaApi api) {
        return new ObjectProvider<>() {
            @Override
            public SchemaApi getObject() {
                return api;
            }

            @Override
            public SchemaApi getObject(Object... args) {
                return api;
            }

            @Override
            public SchemaApi getIfAvailable() {
                return api;
            }

            @Override
            public SchemaApi getIfUnique() {
                return api;
            }
        };
    }

    private static AgentSpec minimalSpec(OutputContract output) {
        return new AgentSpec(
                1,
                new AgentSpec.Profile("demo", null, null, null, "CHAT"),
                new AgentSpec.Instructions(List.of(new AgentSpec.Instructions.Section("main", "hi"))),
                new AgentSpec.ModelPolicy("rp_1", false),
                AgentSpec.ToolSet.empty(),
                List.of(),
                AgentSpec.KnowledgeBinding.empty(),
                AgentSpec.SandboxPolicy.defaults(),
                AgentSpec.ApprovalSettings.empty(),
                AgentSpec.Limits.defaults(),
                null,
                output,
                null, null, null, null, null, null);
    }
}
