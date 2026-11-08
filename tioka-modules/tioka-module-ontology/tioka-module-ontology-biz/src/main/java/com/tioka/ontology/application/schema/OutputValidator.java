package com.tioka.ontology.application.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.tioka.ontology.api.schema.SchemaError;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * networknt 校验 + x-tioka-path 占位（路径目录未接线时跳过）。
 */
public class OutputValidator {

    private final JsonSchemaFactory factory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    public List<SchemaError> validate(JsonNode schema, JsonNode payload) {
        JsonSchema compiled = factory.getSchema(schema);
        Set<ValidationMessage> messages = compiled.validate(payload == null ? NullNode.getInstance() : payload);
        List<SchemaError> errors = new ArrayList<>();
        for (ValidationMessage message : messages) {
            errors.add(new SchemaError(
                    message.getInstanceLocation() == null ? "" : message.getInstanceLocation().toString(),
                    "SCHEMA_VIOLATION",
                    message.getMessage()));
        }
        return errors;
    }
}
