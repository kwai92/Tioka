package com.tioka.ontology.api.rule;

import java.util.List;

/**
 * 审查规则声明（ADR-036「规则求值」/ M3-03 §9）。字段与原文逐项对齐。
 *
 * <pre>
 * ruleId: lease.D.017
 * applies: has(m.feeItems.RENT)                       # 适用条件；false → 不适用
 * requires: [m.feeItems.RENT.abate]                   # 依赖路径；任一未知 → 缺失
 * check: m.feeItems.RENT.abate.freeMonths() >= cfg.minFreeMonths
 * onBlank: NOT_SATISFIED                              # 依赖路径为空白时的结论，缺省 NOT_SATISFIED
 * params: [minFreeMonths]                             # 阈值来自业务系统配置，记配置版本
 * </pre>
 *
 * @param ruleId   规则编号，如 {@code lease.D.017}
 * @param applies  适用条件 CEL 表达式（可空：缺省视为适用）
 * @param requires 依赖路径清单（可空）
 * @param check    结论 CEL 表达式（optional&lt;bool&gt;；结果为 none → 缺失）
 * @param onBlank  依赖路径为「空白」时的结论，缺省 NOT_SATISFIED
 * @param params   阈值参数名清单（由调用方传入 cfg 并记 configVersion）
 */
public record RuleSpec(
        String ruleId,
        String applies,
        List<String> requires,
        String check,
        String onBlank,
        List<String> params) {

    public RuleSpec {
        if (requires == null) {
            requires = List.of();
        }
        if (params == null) {
            params = List.of();
        }
    }
}
