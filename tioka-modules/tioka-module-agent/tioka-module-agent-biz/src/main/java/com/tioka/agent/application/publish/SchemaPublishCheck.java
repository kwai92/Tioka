package com.tioka.agent.application.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.publish.PublishCheck;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.api.spec.AgentSpecJson;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** AST-02 #10：JSON Schema + 预留字段必须为 null。回滚跳过。 */
@Component
@Order(10)
public class SchemaPublishCheck implements PublishCheck {
    private final ObjectMapper mapper;

    public SchemaPublishCheck(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<PublishError> check(AgentSpec spec, PublishContext ctx) {
        if (ctx.rollback()) {
            return List.of();
        }
        List<PublishError> errors = new ArrayList<>();
        for (String msg : AgentSpecJson.validateSchema(mapper, spec)) {
            errors.add(new PublishError("$", "SCHEMA_INVALID", msg));
        }
        // Jackson 常把 JSON null 反序列化为 NullNode，与 Java null 同等视为「未设置」
        if (isSet(spec.charter())) {
            errors.add(new PublishError("/charter", "RESERVED_FIELD", "M1 预留字段 charter 必须为 null"));
        }
        if (isSet(spec.output())) {
            errors.add(new PublishError("/output", "RESERVED_FIELD", "M1 预留字段 output 必须为 null"));
        }
        if (isSet(spec.subAgents())) {
            errors.add(new PublishError("/subAgents", "RESERVED_FIELD", "M1 预留字段 subAgents 必须为 null"));
        }
        if (spec.dataClearance() != null) {
            errors.add(new PublishError("/dataClearance", "RESERVED_FIELD", "M1 预留字段 dataClearance 必须为 null"));
        }
        return errors;
    }

    private static boolean isSet(JsonNode node) {
        return node != null && !node.isNull() && !node.isMissingNode();
    }
}
