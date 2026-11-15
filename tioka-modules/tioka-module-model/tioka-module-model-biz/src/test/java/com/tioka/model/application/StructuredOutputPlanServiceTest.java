package com.tioka.model.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tioka.model.ModelTestSupport.InMemoryModelEntitiesRepository;
import com.tioka.model.api.profile.StructuredCapabilityQueryApi;
import com.tioka.model.api.profile.StructuredOutputCapability;
import com.tioka.model.api.profile.StructuredOutputMode;
import com.tioka.model.api.profile.StructuredOutputPlanApi.CandidateCompile;
import com.tioka.model.application.gateway.StructuredRequestBuilder;
import com.tioka.model.application.routing.RoutingPlanSpecSource;
import com.tioka.model.domain.ModelEntities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCH-06（ADR-066 §2）：Agent 发布期按路由计划枚举候选模型并逐个试编译权威 Schema。
 */
class StructuredOutputPlanServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ModelEntities entities;

    @BeforeEach
    void setUp() {
        entities = new ModelEntities(new InMemoryModelEntitiesRepository());
        entities.putBinding(new ModelEntities.ModelBinding("b1", "gpt", "p1", "gpt-4o"));
        entities.putBinding(new ModelEntities.ModelBinding("b2", "claude", "p1", "claude-3"));
    }

    private StructuredOutputPlanService service(RoutingPlanSpecSource specSource,
                                                ObjectProvider<StructuredCapabilityQueryApi> capabilities) {
        return new StructuredOutputPlanService(specSource, entities, new StructuredRequestBuilder(mapper), capabilities);
    }

    /** 缺省 spec 源：回退 entities 投影行的 primary/fallback（等价 versioned 未装配）。 */
    private static final RoutingPlanSpecSource NO_SPEC = planId -> Optional.empty();

    private void seedPlan() {
        entities.putPlan(new ModelEntities.RoutingPlan("rp_1", "rp_1", "PRIMARY_FALLBACK",
                "b1", "b2", null, true, null));
    }

    private JsonNode schema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.putArray("required").add("name");
        ObjectNode name = schema.putObject("properties").putObject("name");
        name.put("type", "string");
        name.put("description", "名称");
        return schema;
    }

    @Test
    void enumeratesCandidatesAndCompilesAllWithLegacyCapability() {
        seedPlan();

        List<CandidateCompile> results = service(NO_SPEC, null).trialCompile("rp_1", schema(), false);

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(CandidateCompile::compiled));
        assertTrue(results.stream().anyMatch(r -> "gpt@gpt-4o".equals(r.modelRef())));
        assertTrue(results.stream().anyMatch(r -> "claude@claude-3".equals(r.modelRef())));
        assertTrue(results.stream().allMatch(r -> r.reasonCode() == null));
    }

    @Test
    void excludesCandidateThatExceedsModelLimits() {
        seedPlan();
        StructuredOutputCapability tight = new StructuredOutputCapability(
                Set.of(StructuredOutputMode.STRICT_SCHEMA),
                Set.of("type", "properties", "required", "additionalProperties", "description"),
                new StructuredOutputCapability.Limits(1, null, null, null, null),
                Map.of(), false, false,
                StructuredOutputCapability.MaxTokensAdvice.UNSET,
                StructuredOutputCapability.Stability.GA,
                null, null, null, null);
        ObjectProvider<StructuredCapabilityQueryApi> capabilities = providerOf(
                modelRef -> "gpt@gpt-4o".equals(modelRef)
                        ? Optional.of(new StructuredCapabilityQueryApi.CapabilityRecord(tight, "v1"))
                        : Optional.empty());

        List<CandidateCompile> results = service(NO_SPEC, capabilities).trialCompile("rp_1", schema(), false);

        assertEquals(2, results.size());
        CandidateCompile failed = results.stream().filter(r -> !r.compiled()).findFirst().orElseThrow();
        assertEquals("gpt@gpt-4o", failed.modelRef());
        assertEquals("DIALECT_TOO_DEEP", failed.reasonCode());
        assertTrue(results.stream().anyMatch(CandidateCompile::compiled));
    }

    @Test
    void returnsEmptyWhenPlanMissing() {
        assertTrue(service(NO_SPEC, null).trialCompile("rp_missing", schema(), false).isEmpty());
    }

    @Test
    void returnsEmptyWhenSchemaMissing() {
        seedPlan();
        assertTrue(service(NO_SPEC, null).trialCompile("rp_1", null, false).isEmpty());
    }

    @Test
    void ignoresDisabledCandidates() {
        entities.putBinding(new ModelEntities.ModelBinding("b2", "claude", "p1", "claude-3", "chat",
                null, null, 0L, 0L, null, null, false));
        seedPlan();

        List<CandidateCompile> results = service(NO_SPEC, null).trialCompile("rp_1", schema(), false);

        assertEquals(1, results.size());
        assertEquals("gpt@gpt-4o", results.get(0).modelRef());
    }

    @Test
    void resolvesGroupsFromSpecSource() {
        entities.putPlan(new ModelEntities.RoutingPlan("rp_1", "rp_1", "PRIMARY_FALLBACK",
                "b1", null, null, true, null));
        RoutingPlanSpecSource specSource = planId -> Optional.of(new com.tioka.model.application.routing.RoutingPlanSnapshot(
                false,
                com.tioka.model.api.routing.RoutingMode.FIXED,
                Map.of("main", List.of("b1", "b2")),
                Map.of(), Map.of(), null, null, true));

        List<CandidateCompile> results = service(specSource, null).trialCompile("rp_1", schema(), false);

        assertEquals(2, results.size());
        assertFalse(results.stream().anyMatch(r -> r.modelRef() == null));
    }

    private static ObjectProvider<StructuredCapabilityQueryApi> providerOf(StructuredCapabilityQueryApi api) {
        return new ObjectProvider<>() {
            @Override
            public StructuredCapabilityQueryApi getObject() {
                return api;
            }

            @Override
            public StructuredCapabilityQueryApi getObject(Object... args) {
                return api;
            }

            @Override
            public StructuredCapabilityQueryApi getIfAvailable() {
                return api;
            }

            @Override
            public StructuredCapabilityQueryApi getIfUnique() {
                return api;
            }
        };
    }
}
