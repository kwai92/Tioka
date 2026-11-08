package com.tioka.ontology.api.schema;

/**
 * @param pointer JSON Pointer
 * @param code    如 UNSUPPORTED_KEYWORD / REMOTE_REF / HASH_CONFLICT / SCHEMA_VIOLATION
 */
public record SchemaError(String pointer, String code, String message) {
}
