package com.tioka.model.application.routing;

import com.tioka.model.api.routing.Difficulty;
import com.tioka.model.api.routing.RoutingDecider;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * MH-06 内置启发式决策器（ADR-024：现有关键词难度判断降级为内置决策器，不再冠名「决策模型」）。
 * <p>口径（批次文档 §四，确定性纯函数，毫秒级）：
 * <ul>
 *   <li>category：关键词命中 coding/translation/summary/analysis，否则 general；</li>
 *   <li>difficulty：userText ≥ 800 字 HARD，≥ 200 字 MEDIUM，否则 EASY；带工具调用时至少 MEDIUM；</li>
 *   <li>confidence：固定 0.6（启发式信号弱置信）。</li>
 * </ul>
 */
@Component
public class HeuristicRoutingDecider implements RoutingDecider {

    static final double CONFIDENCE = 0.6d;
    static final int HARD_THRESHOLD = 800;
    static final int MEDIUM_THRESHOLD = 200;

    @Override
    public String id() {
        return RoutingDeciderRegistry.ID_HEURISTIC;
    }

    @Override
    public Decision decide(DecisionInput input) {
        String text = input == null || input.userText() == null ? "" : input.userText();
        boolean toolsRequired = input != null && input.hints() != null
                && Boolean.TRUE.equals(input.hints().get("toolsRequired"));
        String category = categorize(text);
        int len = text.length();
        Difficulty difficulty = len >= HARD_THRESHOLD ? Difficulty.HARD
                : len >= MEDIUM_THRESHOLD ? Difficulty.MEDIUM : Difficulty.EASY;
        if (toolsRequired && difficulty == Difficulty.EASY) {
            difficulty = Difficulty.MEDIUM;
        }
        String reason = "启发式: 长度=" + len + ", 类别词=" + category
                + (toolsRequired ? ", 需工具调用" : "");
        return new Decision(category, difficulty, CONFIDENCE, reason);
    }

    private static String categorize(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("代码") || t.contains("编程") || t.contains("code") || t.contains("sql")) {
            return "coding";
        }
        if (t.contains("翻译") || t.contains("translate")) {
            return "translation";
        }
        if (t.contains("总结") || t.contains("摘要") || t.contains("summary") || t.contains("归纳")) {
            return "summary";
        }
        if (t.contains("分析") || t.contains("对比") || t.contains("analyze") || t.contains("review")) {
            return "analysis";
        }
        return "general";
    }

    /** 供测试与调试暴露阈值口径。 */
    static Map<String, Integer> thresholds() {
        return Map.of("hard", HARD_THRESHOLD, "medium", MEDIUM_THRESHOLD);
    }
}
