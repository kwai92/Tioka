package com.tioka.eval.evaluator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.eval.api.spi.EvalScore;
import com.tioka.eval.api.spi.EvalSubject;
import com.tioka.eval.api.spi.Evaluator;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway;
import com.tioka.model.api.gateway.ModelPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 内置评估器：模型打分 LLM-as-a-Judge（ADR-031）。经 {@link ModelGateway} 调用，
 * {@code purpose=EVALUATION} —— 用量计入空间评测预算、不占用户配额（ModelPurpose 口径）。
 * 输出解析失败兜底 score=0 / passed=false（不使运行失败）。
 *
 * <p>依赖纪律（C-8 清单）：ModelGateway 经 ObjectProvider 弱依赖；评测调用无会话上下文，
 * Principal 缺席时以 SYSTEM 兜底（配额归集按 workspace/purpose 维度，不落用户）。
 */
public class LlmJudgeEvaluator implements Evaluator {

    public static final String TYPE = "llm_judge";

    private static final Logger log = LoggerFactory.getLogger(LlmJudgeEvaluator.class);

    private static final String SYSTEM_PROMPT = """
            你是评测裁判。根据「输入」「期望」「实际输出」判断回答质量，只输出一个 JSON 对象，\
            形如 {"score": 0到1之间的小数, "passed": true或false, "reason": "简短理由"}，不要输出任何其他内容。""";

    private final ObjectProvider<ModelGateway> gateway;
    private final PrincipalHolder principals;
    private final ObjectMapper mapper;

    public LlmJudgeEvaluator(ObjectProvider<ModelGateway> gateway,
                             PrincipalHolder principals,
                             ObjectMapper mapper) {
        this.gateway = gateway;
        this.principals = principals;
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public EvalScore score(EvalSubject subject, Map<String, Object> config) {
        Objects.requireNonNull(subject, "subject");
        ModelGateway gw = gateway.getIfAvailable();
        if (gw == null) {
            return new EvalScore(0.0, false, "模型网关不可用，无法执行 llm_judge");
        }
        Principal principal = principals.find().orElse(systemPrincipal());
        ModelCallContext ctx = new ModelCallContext(principal, null, subject.turnId(),
                null, null, ModelPurpose.EVALUATION);
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", userPrompt(subject)));
        try {
            ModelGateway.ChatResult result = gw.chat(ctx,
                    new ModelGateway.ChatRequest(messages, null, null, 512), d -> { });
            return parseJudgeOutput(result.content());
        } catch (RuntimeException e) {
            log.warn("llm_judge 调用失败 turnId={}: {}", subject.turnId(), e.toString());
            return new EvalScore(0.0, false, "llm_judge 调用失败: " + e.getMessage());
        }
    }

    private String userPrompt(EvalSubject subject) {
        return "输入: " + EvalTexts.text(subject.input()) + "\n"
                + "期望: " + EvalTexts.text(subject.expected()) + "\n"
                + "实际输出: " + EvalTexts.text(subject.actual());
    }

    private EvalScore parseJudgeOutput(String content) {
        String json = extractJsonObject(content == null ? "" : content);
        if (json == null) {
            return new EvalScore(0.0, false, "llm_judge 输出解析失败: " + truncate(content));
        }
        try {
            JsonNode node = mapper.readTree(json);
            Double score = node.hasNonNull("score") ? node.get("score").asDouble() : null;
            boolean passed = node.path("passed").asBoolean(score != null && score >= 0.6);
            String reason = node.hasNonNull("reason") ? node.get("reason").asText() : null;
            if (score == null || score < 0.0 || score > 1.0) {
                return new EvalScore(0.0, false, "llm_judge score 越界: " + truncate(content));
            }
            return new EvalScore(score, passed, reason == null ? "模型打分" : reason);
        } catch (Exception e) {
            return new EvalScore(0.0, false, "llm_judge 输出解析失败: " + truncate(content));
        }
    }

    /** 宽松截取首个 {...}（容忍代码围栏与前导说明）。 */
    static String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return raw.substring(start, end + 1);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    private static Principal systemPrincipal() {
        return new Principal("0", null, PrincipalType.SYSTEM,
                java.util.Set.of(), java.util.Set.of(), "system");
    }
}
