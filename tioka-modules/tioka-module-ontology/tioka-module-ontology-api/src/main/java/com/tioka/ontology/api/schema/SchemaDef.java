package com.tioka.ontology.api.schema;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Schema 登记表条目（ADR-037）。
 */
public record SchemaDef(
        String schemaKey,
        int version,
        JsonNode jsonSchema,
        String ownerPack,
        String defaultLabel,
        String contentHash,
        String status
) {
    public String ref() {
        return schemaKey + "@" + version;
    }
}
