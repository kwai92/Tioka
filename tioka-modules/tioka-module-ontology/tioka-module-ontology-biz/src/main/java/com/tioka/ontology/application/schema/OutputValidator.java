package com.tioka.ontology.application.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.tioka.ontology.api.schema.SchemaError;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * networknt 校验（2020-12，全部错误不首错停止）+ x-tioka-path 取值提取（SCH-03 最小实现）。
 * x-tioka-path 标注字段的「路径存在」检查依赖 ONT-01 路径目录，未接线前跳过（登记偏差）。
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

    /** x-tioka-path 字段取值（payload 中该字段值）。 */
    public record PathValue(String pointer, String value) {}

    /**
     * SCH-03 最小实现：按 schema 提取标注 {@code x-tioka-path: true} 的字段在 payload 中的取值。
     * 路径存在性检查（ADR-036 路径目录）随 ONT-01 接线补齐。
     */
    public List<PathValue> xTiokaPathValues(JsonNode schema, JsonNode payload) {
        List<PathValue> out = new ArrayList<>();
        walkPath(schema, payload, "", out);
        return out;
    }

    private void walkPath(JsonNode schemaNode, JsonNode payloadNode, String pointer, List<PathValue> out) {
        if (schemaNode == null || payloadNode == null || payloadNode.isMissingNode() || payloadNode.isNull()) {
            return;
        }
        if (schemaNode.isObject()
                && schemaNode.path("x-tioka-path").asBoolean(false)
                && payloadNode.isTextual()) {
            out.add(new PathValue(pointer.isEmpty() ? "/" : pointer, payloadNode.asText()));
            return;
        }
        if (schemaNode.isObject() && payloadNode.isObject()) {
            JsonNode props = schemaNode.get("properties");
            if (props != null && props.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = props.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> e = fields.next();
                    walkPath(e.getValue(), payloadNode.get(e.getKey()),
                            pointer + "/" + e.getKey(), out);
                }
            }
            return;
        }
        if (schemaNode.isArray() || schemaNode.isObject() && schemaNode.has("items")) {
            JsonNode items = schemaNode.get("items");
            if (items != null && payloadNode.isArray()) {
                for (int i = 0; i < payloadNode.size(); i++) {
                    walkPath(items, payloadNode.get(i), pointer + "/" + i, out);
                }
            }
        }
    }
}
