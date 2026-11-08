package com.tioka.ontology.application.def;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.ontology.api.schema.SchemaDef;
import com.tioka.ontology.application.schema.SchemaRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaDeriverTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private SchemaRegistry registry;
    private SchemaDeriver deriver;

    @BeforeEach
    void setUp() {
        registry = new SchemaRegistry(mapper);
        deriver = new SchemaDeriver(mapper, registry);
    }

    @Test
    void derivesObjectAndStatementSchemasWithOwnerPack() {
        List<SchemaDef> published = deriver.deriveAndPublish(
                PathCatalogBuilderTest.sampleDef(), 1, "lease");

        assertEquals(2, published.size());
        assertTrue(registry.get("lease.contract.feeItem@1").isPresent());
        assertTrue(registry.get("lease.contract.statement.OBLIGATION@1").isPresent());

        SchemaDef feeItem = registry.get("lease.contract.feeItem@1").orElseThrow();
        assertEquals("lease", feeItem.ownerPack());
        assertEquals("PUBLISHED", feeItem.status());
        assertTrue(feeItem.jsonSchema().path("properties").has("sched"));
        assertTrue(feeItem.jsonSchema().path("properties").path("sched").path("properties").has("segments"));
    }
}
