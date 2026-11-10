package com.tioka.ontology.application.instance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** JsonNode → JSON 字符串（DO 持久化用）；null → null。 */
final class ModelDraftJson {

    private ModelDraftJson() {
    }

    static String stringify(JsonNode node, ObjectMapper mapper) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("model JSON 序列化失败", e);
        }
    }
}
