package com.tioka.model.api.prompt;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AST-07（ADR-041 §4）：无逻辑模板渲染引擎纯函数测试（无 Spring / 无 JDBC）。
 */
class PromptRenderEngineTest {

    private static PromptTemplateSpec spec(List<PromptTemplateSpec.Section> sections,
                                           List<PromptTemplateSpec.Variable> variables) {
        return new PromptTemplateSpec("sys.test", PromptTemplateSpec.SCOPE_SYSTEM, null, "chat_snippet",
                sections, variables, PromptTemplateSpec.FORMAT_TEXT, null, null, Map.of());
    }

    @Test
    void substitutesVariablesAndAppliesDefaults() {
        PromptTemplateSpec s = spec(
                List.of(new PromptTemplateSpec.Section(PromptTemplateSpec.ROLE_USER,
                        "你好 {{name}}，字数上限 {{maxWords}}")),
                List.of(
                        new PromptTemplateSpec.Variable("name", PromptTemplateSpec.TYPE_STRING, true, "朋友",
                                "称呼"),
                        new PromptTemplateSpec.Variable("maxWords", PromptTemplateSpec.TYPE_NUMBER, false, "200",
                                "上限")));
        List<PromptRenderEngine.RenderedSection> out = PromptRenderEngine.render(s, Map.of());
        assertEquals(1, out.size());
        // 未给变量时套用默认值；显式传入则覆盖默认
        assertEquals("你好 朋友，字数上限 200", out.get(0).body());
        assertEquals("你好 小明，字数上限 100",
                PromptRenderEngine.render(s, Map.of("name", "小明", "maxWords", 100)).get(0).body());
    }

    @Test
    void missingRequiredVariableThrows() {
        PromptTemplateSpec s = spec(
                List.of(new PromptTemplateSpec.Section(PromptTemplateSpec.ROLE_USER, "内容：{{content}}")),
                List.of(new PromptTemplateSpec.Variable("content", PromptTemplateSpec.TYPE_STRING, true,
                        "待处理正文")));
        PromptRenderException ex = assertThrows(PromptRenderException.class,
                () -> PromptRenderEngine.render(s, Map.of()));
        assertTrue(ex.reason().contains("content"));
    }

    @Test
    void conditionalSectionRendersOnlyWhenTruthy() {
        PromptTemplateSpec s = spec(
                List.of(new PromptTemplateSpec.Section(PromptTemplateSpec.ROLE_USER,
                        "结果{{#show}}：有{{/show}}。")),
                List.of(new PromptTemplateSpec.Variable("show", PromptTemplateSpec.TYPE_BOOLEAN, false, null,
                        "是否展示")));
        assertEquals("结果：有。", PromptRenderEngine.render(s, Map.of("show", true)).get(0).body());
        assertEquals("结果。", PromptRenderEngine.render(s, Map.of("show", false)).get(0).body());
        assertEquals("结果。", PromptRenderEngine.render(s, Map.of()).get(0).body());
    }

    @Test
    void invertedSectionRendersWhenFalsy() {
        PromptTemplateSpec s = spec(
                List.of(new PromptTemplateSpec.Section(PromptTemplateSpec.ROLE_USER,
                        "{{^items}}没有数据{{/items}}{{#items}}有数据{{/items}}")),
                List.of(new PromptTemplateSpec.Variable("items", PromptTemplateSpec.TYPE_LIST, false, null,
                        "条目")));
        assertEquals("没有数据", PromptRenderEngine.render(s, Map.of()).get(0).body());
        assertEquals("有数据", PromptRenderEngine.render(s, Map.of("items", List.of("a"))).get(0).body());
    }

    @Test
    void listExpansionRepeatsPerItem() {
        PromptTemplateSpec s = spec(
                List.of(new PromptTemplateSpec.Section(PromptTemplateSpec.ROLE_USER,
                        "{{#items}}{{.}},{{/items}}")),
                List.of(new PromptTemplateSpec.Variable("items", PromptTemplateSpec.TYPE_LIST, true,
                        "条目列表")));
        assertEquals("a,b,", PromptRenderEngine.render(s, Map.of("items", List.of("a", "b"))).get(0).body());
    }

    @Test
    void listOfMapsExposesNamedFields() {
        PromptTemplateSpec s = spec(
                List.of(new PromptTemplateSpec.Section(PromptTemplateSpec.ROLE_USER,
                        "{{#users}}{{name}};{{/users}}")),
                List.of(new PromptTemplateSpec.Variable("users", PromptTemplateSpec.TYPE_LIST, true,
                        "用户列表")));
        List<Map<String, Object>> users = List.of(Map.of("name", "A"), Map.of("name", "B"));
        assertEquals("A;B;", PromptRenderEngine.render(s, Map.of("users", users)).get(0).body());
    }

    @Test
    void unclosedSectionThrows() {
        PromptTemplateSpec s = spec(
                List.of(new PromptTemplateSpec.Section(PromptTemplateSpec.ROLE_USER, "{{#x}}未闭合")),
                List.of(new PromptTemplateSpec.Variable("x", PromptTemplateSpec.TYPE_BOOLEAN, false, null,
                        "开关")));
        assertThrows(PromptRenderException.class, () -> PromptRenderEngine.render(s, Map.of("x", true)));
    }

    @Test
    void doesNotEvaluateExpressions() {
        // 非标识符标签被当作普通变量名查表，绝不会被求值；未声明则渲染为空串
        PromptTemplateSpec s = spec(
                List.of(new PromptTemplateSpec.Section(PromptTemplateSpec.ROLE_USER, "值=[{{1+1}}]")),
                List.of());
        assertEquals("值=[]", PromptRenderEngine.render(s, Map.of()).get(0).body());
    }
}
