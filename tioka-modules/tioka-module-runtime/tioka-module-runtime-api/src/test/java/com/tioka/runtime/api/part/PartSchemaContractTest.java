package com.tioka.runtime.api.part;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PartSchemaContractTest {

    @Test
    void schemaLoadsAndAcceptsTextPart() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = getClass().getResourceAsStream("/schema/part-v0.json")) {
            assertNotNull(in, "part-v0.json must exist on classpath");
            JsonNode schemaNode = mapper.readTree(in);
            JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(schemaNode);
            JsonNode part = mapper.readTree("{\"id\":\"p1\",\"type\":\"text\",\"text\":\"hello\"}");
            Set<ValidationMessage> errors = schema.validate(part);
            assertTrue(errors.isEmpty(), errors::toString);
        }
        assertEquals(18, PartType.values().length);
        assertEquals(PartType.TEXT, PartType.fromWire("text"));
        assertEquals(PartType.INTERACTIVE_CARD, PartType.fromWire("interactive_card"));
        assertEquals(PartType.APPROVAL_CARD, PartType.fromWire("approval_card"));
    }
}
