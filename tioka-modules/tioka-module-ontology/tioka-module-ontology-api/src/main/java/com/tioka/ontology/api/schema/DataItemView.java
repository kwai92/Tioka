package com.tioka.ontology.api.schema;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@link DataItemReader} 返回视图（ADR-066）。
 */
public record DataItemView(
        String itemId,
        String schemaRef,
        DataItemOutcome outcome,
        String reasonCode,
        JsonNode payload
) {}
