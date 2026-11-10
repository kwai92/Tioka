package com.tioka.ontology.api.rule;

import java.util.List;
import java.util.Map;

/**
 * rule.evaluate 结果（M3-03 §9）。结论四值 + 待复核标记。
 *
 * <ul>
 *   <li>{@code conclusion}：SATISFIED / NOT_SATISFIED / MISSING / NOT_APPLICABLE</li>
 *   <li>{@code touched}：求值访问过的路径（路径、值、状态、来源），直接作为 Finding 的 modelRefs</li>
 *   <li>{@code params}：参与求值的阈值 {name, value, configVersion}</li>
 *   <li>{@code error}：超时 / 表达式错误时的原因；结论记 MISSING 并告警，不让审查整体失败</li>
 *   <li>{@code needsReview}：依赖「待复核」取值时为 true（待复核值按已知值参与计算）</li>
 * </ul>
 */
public record RuleResult(
        Conclusion conclusion,
        List<Touched> touched,
        List<Param> params,
        String error,
        boolean needsReview) {

    public enum Conclusion {
        SATISFIED,
        NOT_SATISFIED,
        MISSING,
        NOT_APPLICABLE
    }

    /** 求值访问过的取值：路径、值、状态、来源。 */
    public record Touched(String path, Object value, String status, List<Map<String, Object>> sources) {
        public Touched {
            if (sources == null) {
                sources = List.of();
            }
        }
    }

    /** 阈值参数：业务系统传入，记配置版本。 */
    public record Param(String name, Object value, String configVersion) {
    }

    public static RuleResult of(Conclusion conclusion, List<Touched> touched, List<Param> params) {
        return new RuleResult(conclusion, touched, params, null, false);
    }

    public static RuleResult error(String message, List<Touched> touched, List<Param> params) {
        return new RuleResult(Conclusion.MISSING, touched, params, message, false);
    }
}
