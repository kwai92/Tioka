package com.tioka.agent.application.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.publish.PublishCheck;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.api.spec.AgentSpecJson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** AST-02 #10：JSON Schema + 预留字段必须为 null。回滚跳过。 */
@Component
@Order(10)
public class SchemaPublishCheck implements PublishCheck {
    private final ObjectMapper mapper;
    private final boolean charterEnabled;

    public SchemaPublishCheck(ObjectMapper mapper,
                              @Value("${tioka.trust.charter.enabled:false}") boolean charterEnabled) {
        this.mapper = mapper;
        this.charterEnabled = charterEnabled;
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
        // TRU-01：特性开启后由 CharterPublishCheck 校验；关闭时仍视为 M1 预留（不改变 M2 行为）
        if (!charterEnabled && isSet(spec.charter())) {
            errors.add(new PublishError("/charter", "RESERVED_FIELD", "M1 预留字段 charter 必须为 null"));
        }
        // SCH-02：output 已解冻为 OutputContract；schemaRef 由 OutputContractPublishCheck 校验
        if (isSet(spec.subAgents())) {
            errors.add(new PublishError("/subAgents", "RESERVED_FIELD", "M1 预留字段 subAgents 必须为 null"));
        }
        // DAT-01 解冻（M3-02）：dataClearance 校验合法枚举值（null = 缺省 INTERNAL）
        if (spec.dataClearance() != null && !VALID_CLEARANCE.contains(spec.dataClearance())) {
            errors.add(new PublishError("/dataClearance", "SCHEMA_INVALID",
                    "dataClearance 须为 PUBLIC/INTERNAL/CONFIDENTIAL/SECRET 之一"));
        }
        if (spec.datasets() != null) {
            for (int i = 0; i < spec.datasets().size(); i++) {
                String d = spec.datasets().get(i);
                if (d == null || d.isBlank()) {
                    errors.add(new PublishError("/datasets/" + i, "SCHEMA_INVALID",
                            "datasets 条目不得为空"));
                }
            }
        }
        return errors;
    }

    private static final Set<String> VALID_CLEARANCE =
            Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "SECRET");

    private static boolean isSet(JsonNode node) {
        return node != null && !node.isNull() && !node.isMissingNode();
    }
}
