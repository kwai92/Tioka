package com.tioka.ontology.application.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tioka.ontology.api.schema.SchemaDef;
import com.tioka.ontology.api.schema.SchemaError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaRegistryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private SchemaRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SchemaRegistry(mapper);
    }

    @Test
    void rejectsIfThenElse() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("if", mapper.createObjectNode().put("type", "object"));
        schema.set("then", mapper.createObjectNode().put("type", "object"));

        SchemaPublishException ex = assertThrows(
                SchemaPublishException.class,
                () -> registry.publish("demo.bad", 1, schema, "platform"));
        assertTrue(ex.errors().stream().anyMatch(e -> "UNSUPPORTED_KEYWORD".equals(e.code())));
    }

    @Test
    void rejectsRemoteRef() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("$ref", "https://example.com/remote.json");

        SchemaPublishException ex = assertThrows(
                SchemaPublishException.class,
                () -> registry.publish("demo.remote", 1, schema, "platform"));
        assertTrue(ex.errors().stream().anyMatch(e -> "REMOTE_REF".equals(e.code())));
    }

    @Test
    void rejectsContentHashConflict() {
        ObjectNode a = mapper.createObjectNode();
        a.put("type", "object");
        a.put("title", "A");
        ObjectNode b = mapper.createObjectNode();
        b.put("type", "object");
        b.put("title", "B");

        registry.publish("demo.x", 1, a, "platform");
        SchemaPublishException ex = assertThrows(
                SchemaPublishException.class,
                () -> registry.publish("demo.x", 1, b, "platform"));
        assertTrue(ex.errors().stream().anyMatch(e -> "HASH_CONFLICT".equals(e.code())));
    }

    @Test
    void acceptsSubsetAndResolvesInternalRef() {
        ObjectNode other = mapper.createObjectNode();
        other.put("type", "string");
        registry.publish("demo.other", 1, other, "platform");

        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode name = props.putObject("name");
        name.put("$ref", "tioka://schema/demo.other@1");
        schema.putArray("required").add("name");

        SchemaDef published = registry.publish("demo.ok", 1, schema, "platform");
        assertEquals("PUBLISHED", published.status());
        assertTrue(registry.get("demo.ok@1").isPresent());

        ObjectNode bad = mapper.createObjectNode();
        List<SchemaError> errors = registry.validate("demo.ok@1", bad);
        assertTrue(errors.stream().anyMatch(e -> "SCHEMA_VIOLATION".equals(e.code())));
    }

    /**
     * SCH-02 验收补充（ADR-037）：发给模型的方言可去掉 minLength，
     * 但平台仍用权威 Schema 拦住违反该约束的值。
     */
    @Test
    void authoritativeConstraintsStillEnforcedWhenDialectStripsKeyword() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.putArray("required").add("name");
        ObjectNode props = schema.putObject("properties");
        ObjectNode name = props.putObject("name");
        name.put("type", "string");
        name.put("minLength", 2);
        name.put("description", "名称");

        registry.publish("demo.minlen", 1, schema, "platform");

        ObjectNode tooShort = mapper.createObjectNode();
        tooShort.put("name", "a");
        List<SchemaError> errors = registry.validate("demo.minlen@1", tooShort);
        assertTrue(errors.stream().anyMatch(e -> "SCHEMA_VIOLATION".equals(e.code())),
                "权威 Schema 的 minLength 必须仍拦住短值");

        ObjectNode ok = mapper.createObjectNode();
        ok.put("name", "ab");
        assertTrue(registry.validate("demo.minlen@1", ok).isEmpty());
    }
}
