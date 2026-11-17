package com.tioka.model.api.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AST-07（ADR-041 §4）：Prompt 模板 spec 序列化与占位符/变量解析。
 */
class PromptTemplateSpecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static PromptTemplateSpec sample() {
        return new PromptTemplateSpec(
                "sys.sample", PromptTemplateSpec.SCOPE_SYSTEM, null, "summarize",
                List.of(
                        new PromptTemplateSpec.Section(PromptTemplateSpec.ROLE_SYSTEM,
                                "你是摘要助手，上限 {{maxWords}} 字。"),
                        new PromptTemplateSpec.Section(PromptTemplateSpec.ROLE_USER,
                                "正文：{{content}}{{#tags}} 标签：{{.}}{{/tags}}")),
                List.of(
                        new PromptTemplateSpec.Variable("content", PromptTemplateSpec.TYPE_STRING, true,
                                "待摘要正文"),
                        new PromptTemplateSpec.Variable("maxWords", PromptTemplateSpec.TYPE_NUMBER, false, "200",
                                "字数上限"),
                        new PromptTemplateSpec.Variable("tags", PromptTemplateSpec.TYPE_LIST, false, "标签")),
                PromptTemplateSpec.FORMAT_TEXT, null, "sys.chat",
                Map.of("content", "（正文）", "maxWords", "200"));
    }

    @Test
    void jsonRoundtripPreservesFields() {
        PromptTemplateSpec original = sample();
        PromptTemplateSpec parsed = PromptTemplateSpec.fromJson(MAPPER, original.toJson(MAPPER));

        assertEquals(original.name(), parsed.name());
        assertEquals(original.scope(), parsed.scope());
        assertEquals(original.purpose(), parsed.purpose());
        assertEquals(original.suggestedAlias(), parsed.suggestedAlias());
        assertEquals(original.sections().size(), parsed.sections().size());
        assertEquals(original.variables().size(), parsed.variables().size());
        assertEquals("sys.sample", parsed.name());
        // 规范化 JSON 顶层键排序稳定（与 governance ConfigSpecNormalizer 同口径）
        String canonical = BuiltinPromptTemplates.canonicalJson(MAPPER, original);
        assertTrue(canonical.indexOf("\"name\"") < canonical.indexOf("\"outputFormat\""));
        assertTrue(canonical.indexOf("\"purpose\"") < canonical.indexOf("\"scope\""));
    }

    @Test
    void placeholdersOnlyCollectNames() {
        java.util.Set<String> names = sample().placeholders();
        assertTrue(names.contains("maxWords"));
        assertTrue(names.contains("content"));
        assertTrue(names.contains("tags"));
        // 闭合标签与 {{.}} 不算占位符
        assertEquals(3, names.size());
    }

    @Test
    void declaredNamesMatchesVariables() {
        java.util.Set<String> declared = sample().declaredNames();
        assertEquals(java.util.Set.of("content", "maxWords", "tags"), declared);
    }

    @Test
    void normalizeScopeLowercasesAndTrims() {
        assertEquals("system", PromptTemplateSpec.normalizeScope(" System "));
        assertEquals("workspace", PromptTemplateSpec.normalizeScope("WORKSPACE"));
        assertNull(PromptTemplateSpec.normalizeScope(null));
    }

    @Test
    void nonObjectJsonReturnsNull() {
        assertNull(PromptTemplateSpec.fromJson(MAPPER, null));
        assertNull(PromptTemplateSpec.fromJson(MAPPER, MAPPER.createArrayNode()));
    }
}
