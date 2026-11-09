package com.tioka.model.application.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.security.Principal;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway;
import com.tioka.model.api.gateway.ModelPurpose;
import com.tioka.model.api.routing.Difficulty;
import com.tioka.model.api.routing.RoutingDecider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MH-06 内置大模型决策器：purpose=decision 的 binding 以固定提示词输出 JSON
 * （ADR-024「大模型决策」）。经 {@link ModelGateway} 自身调用；防递归由执行侧保证
 * （purpose=DECISION 的调用不走模式路由）。
 * <p>口径偏差（批次文档 §六登记）：Prompt 域 sys.* 模板机制尚未建立，固定提示词先落本类常量，
 * 待 Prompt 域 sys 机制后迁移为 sys.routing_decision 模板。
 */
@Component
public class LlmRoutingDecider implements RoutingDecider {

    static final String DECISION_PROMPT = """
            你是模型路由决策器。判断用户输入的任务类别与难度，只输出一个 JSON 对象，不要输出其他内容：
            {"category":"general|coding|translation|summary|analysis","difficulty":"EASY|MEDIUM|HARD","confidence":0到1之间的小数,"reason":"一句话理由"}
            """;

    private static final int MAX_TOKENS = 200;

    private final ObjectProvider<ModelGateway> gateway;
    private final ObjectMapper mapper;

    public LlmRoutingDecider(ObjectProvider<ModelGateway> gateway, ObjectMapper mapper) {
        this.gateway = gateway;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return RoutingDeciderRegistry.ID_LLM;
    }

    @Override
    public Decision decide(DecisionInput input) {
        ModelGateway gw = gateway.getIfAvailable();
        if (gw == null) {
            throw new IllegalStateException("ModelGateway 未装配，llm 决策器不可用");
        }
        String userText = input == null || input.userText() == null ? "" : input.userText();
        // 防递归：决策调用 purpose=DECISION、thread/turn 置空（不冻结、不触发模式路由）
        Object principalObj = input != null && input.hints() != null ? input.hints().get("principal") : null;
        if (!(principalObj instanceof Principal principal)) {
            throw new IllegalStateException("llm 决策器缺少调用上下文（hints.principal）");
        }
        String agentId = input.hints().get("agentId") instanceof String s ? s : null;
        ModelCallContext ctx = new ModelCallContext(principal, null, null, agentId, null, ModelPurpose.DECISION);
        ModelGateway.ChatResult result = gw.chat(ctx,
                new ModelGateway.ChatRequest(
                        List.of(Map.of("role", "system", "content", DECISION_PROMPT),
                                Map.of("role", "user", "content", userText)),
                        List.of(), null, MAX_TOKENS),
                null);
        return parse(result.content());
    }

    /** 解析决策 JSON；解析失败抛异常交执行侧走兜底组。 */
    Decision parse(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("llm 决策器空输出");
        }
        String json = extractJson(content);
        JsonNode n;
        try {
            n = mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("llm 决策器输出非 JSON: " + content, e);
        }
        String category = n.path("category").asText("general");
        Difficulty difficulty = parseDifficulty(n.path("difficulty").asText("MEDIUM"));
        double confidence = n.path("confidence").asDouble(0.5d);
        return new Decision(category, difficulty, Math.min(1d, Math.max(0d, confidence)),
                n.path("reason").asText("llm 决策"));
    }

    static String extractJson(String content) {
        String s = content.trim();
        if (s.startsWith("```")) {
            int first = s.indexOf('\n');
            int last = s.lastIndexOf("```");
            if (first >= 0 && last > first) {
                s = s.substring(first + 1, last).trim();
            }
        }
        int brace = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (brace >= 0 && end > brace) {
            s = s.substring(brace, end + 1);
        }
        return s;
    }

    private static Difficulty parseDifficulty(String v) {
        try {
            return Difficulty.valueOf(v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Difficulty.MEDIUM;
        }
    }
}
