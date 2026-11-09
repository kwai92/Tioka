package com.tioka.model.application.routing;

import com.tioka.model.api.routing.Difficulty;
import com.tioka.model.api.routing.RoutingDecider;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MH-06：启发式决策器确定性口径（批次文档 §四）。
 */
class HeuristicRoutingDeciderTest {

    private final HeuristicRoutingDecider decider = new HeuristicRoutingDecider();

    @Test
    void idIsHeuristic() {
        assertEquals("heuristic", decider.id());
    }

    @Test
    void shortTextIsEasyGeneral() {
        RoutingDecider.Decision d = decider.decide(new RoutingDecider.DecisionInput(
                "你好", null, null, Map.of()));
        assertEquals("general", d.category());
        assertEquals(Difficulty.EASY, d.difficulty());
        assertEquals(HeuristicRoutingDecider.CONFIDENCE, d.confidence());
        assertTrue(d.reason().contains("启发式"));
    }

    @Test
    void longTextIsHard() {
        String longText = "分".repeat(801);
        RoutingDecider.Decision d = decider.decide(new RoutingDecider.DecisionInput(
                longText, null, null, Map.of()));
        assertEquals(Difficulty.HARD, d.difficulty());
    }

    @Test
    void keywordMapsCategoryAndToolsRaiseDifficulty() {
        RoutingDecider.Decision d = decider.decide(new RoutingDecider.DecisionInput(
                "帮我写代码", null, null, Map.of("toolsRequired", true)));
        assertEquals("coding", d.category());
        assertEquals(Difficulty.MEDIUM, d.difficulty(), "带工具调用时至少 MEDIUM");
    }

    @Test
    void translationKeywordMapped() {
        RoutingDecider.Decision d = decider.decide(new RoutingDecider.DecisionInput(
                "请翻译这段话", null, null, Map.of()));
        assertEquals("translation", d.category());
    }
}
