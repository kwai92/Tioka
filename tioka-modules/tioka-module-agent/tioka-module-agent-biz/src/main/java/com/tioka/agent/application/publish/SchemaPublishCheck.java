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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** AST-02 #10：JSON Schema + 预留字段校验。回滚跳过。 */
@Component
@Order(10)
public class SchemaPublishCheck implements PublishCheck {
    private final ObjectMapper mapper;
    private final boolean charterEnabled;
    private final boolean delegateEnabled;

    public SchemaPublishCheck(ObjectMapper mapper,
                              @Value("${tioka.trust.charter.enabled:false}") boolean charterEnabled,
                              @Value("${tioka.runtime.delegate.enabled:false}") boolean delegateEnabled) {
        this.mapper = mapper;
        this.charterEnabled = charterEnabled;
        this.delegateEnabled = delegateEnabled;
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
        // MAS-01〜03 / ADR-027：委派特性开启时 subAgents 解冻为 {"allowed":[agentId...]}。
        // 关闭时维持 M1 预留口径（须 null），避免改变既有行为。
        if (!delegateEnabled) {
            if (isSet(spec.subAgents())) {
                errors.add(new PublishError("/subAgents", "RESERVED_FIELD",
                        "M1 预留字段 subAgents 必须为 null（委派特性未开启）"));
            }
        } else {
            errors.addAll(validateSubAgents(spec.subAgents(), ctx.agentId()));
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

    /**
     * subAgents 权威形态（M3-01 §RT-12、ADR-027、agent-spec-v1.json）：
     * {@code {"allowed":["agv_..."]}}，由 {@code DelegateToolProvider.allowedByParent} 消费。
     *
     * <p>发布期只做结构与自引用校验：①目标 Agent 是否发布由运行时
     * {@code AgentSnapshotApi.currentPublished} 判定；②不在发布期强校验引用存在性，
     * 是为了避免父子互相引用时的发布顺序死锁（父先发布需子已发布、子先发布需父已发布）。
     */
    private static List<PublishError> validateSubAgents(JsonNode sub, String selfAgentId) {
        if (!isSet(sub)) {
            return List.of();
        }
        if (!sub.isObject()) {
            return List.of(new PublishError("/subAgents", "SCHEMA_INVALID",
                    "subAgents 须为对象 {\"allowed\":[agentId...]} 或 null"));
        }
        JsonNode allowed = sub.get("allowed");
        if (allowed == null || allowed.isNull() || !allowed.isArray()) {
            return List.of(new PublishError("/subAgents/allowed", "SCHEMA_INVALID",
                    "subAgents.allowed 必填且须为 agentId 数组"));
        }
        if (allowed.isEmpty()) {
            return List.of(new PublishError("/subAgents/allowed", "SCHEMA_INVALID",
                    "subAgents.allowed 不得为空数组（无可委派成员时请把 subAgents 置为 null）"));
        }
        List<PublishError> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < allowed.size(); i++) {
            JsonNode n = allowed.get(i);
            if (n == null || !n.isTextual() || n.asText().isBlank()) {
                errors.add(new PublishError("/subAgents/allowed/" + i, "SCHEMA_INVALID",
                        "成员须为非空 agentId 字符串"));
                continue;
            }
            String agentId = n.asText();
            if (!seen.add(agentId)) {
                errors.add(new PublishError("/subAgents/allowed/" + i, "SCHEMA_INVALID",
                        "重复的可委派 agentId: " + agentId));
            }
            if (agentId.equals(selfAgentId)) {
                errors.add(new PublishError("/subAgents/allowed/" + i, "SCHEMA_INVALID",
                        "不得把自身列为可委派子 Agent（自委派环）"));
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
