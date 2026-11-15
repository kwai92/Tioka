package com.tioka.model.api.prompt;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AST-07（ADR-041 §4）：Prompt 模板发布期校验规则测试。
 */
class PromptTemplateValidatorTest {

    private static PromptTemplateSpec.Section user(String body) {
        return new PromptTemplateSpec.Section(PromptTemplateSpec.ROLE_USER, body);
    }

    private static boolean hasError(List<PromptTemplateValidator.Issue> issues, String code) {
        return issues.stream()
                .anyMatch(i -> code.equals(i.code())
                        && PromptTemplateValidator.SEVERITY_ERROR.equals(i.severity()));
    }

    @Test
    void validTemplatePasses() {
        PromptTemplateSpec spec = new PromptTemplateSpec(
                "sys.ok", PromptTemplateSpec.SCOPE_SYSTEM, null, "summarize",
                List.of(user("正文：{{content}}")),
                List.of(new PromptTemplateSpec.Variable("content", PromptTemplateSpec.TYPE_STRING, true,
                        "正文")),
                PromptTemplateSpec.FORMAT_TEXT, null, "sys.chat",
                Map.of("content", "（正文）"));
        List<PromptTemplateValidator.Issue> issues = PromptTemplateValidator.validate(spec);
        assertTrue(PromptTemplateValidator.ok(issues), "应通过校验: " + issues);
    }

    @Test
    void systemScopeNameMustStartWithSys() {
        PromptTemplateSpec spec = new PromptTemplateSpec(
                "plain", PromptTemplateSpec.SCOPE_SYSTEM, null, "summarize",
                List.of(user("hi")), List.of(), PromptTemplateSpec.FORMAT_TEXT, null, null, Map.of());
        assertTrue(hasError(PromptTemplateValidator.validate(spec), "PROMPT_NAME_SCOPE_MISMATCH"));
    }

    @Test
    void undefinedPlaceholderIsError() {
        PromptTemplateSpec spec = new PromptTemplateSpec(
                "sys.x", PromptTemplateSpec.SCOPE_SYSTEM, null, "chat_snippet",
                List.of(user("你好 {{missing}}")),
                List.of(),
                PromptTemplateSpec.FORMAT_TEXT, null, null, Map.of());
        assertTrue(hasError(PromptTemplateValidator.validate(spec), "PROMPT_PLACEHOLDER_UNDEFINED"));
    }

    @Test
    void unusedVariableIsError() {
        PromptTemplateSpec spec = new PromptTemplateSpec(
                "sys.x", PromptTemplateSpec.SCOPE_SYSTEM, null, "chat_snippet",
                List.of(user("固定文本")),
                List.of(new PromptTemplateSpec.Variable("unused", PromptTemplateSpec.TYPE_STRING, false,
                        "多余变量")),
                PromptTemplateSpec.FORMAT_TEXT, null, null, Map.of());
        assertTrue(hasError(PromptTemplateValidator.validate(spec), "PROMPT_VARIABLE_UNUSED"));
    }

    @Test
    void duplicateVariableNameIsError() {
        PromptTemplateSpec spec = new PromptTemplateSpec(
                "sys.x", PromptTemplateSpec.SCOPE_SYSTEM, null, "chat_snippet",
                List.of(user("{{a}}{{b}}")),
                List.of(
                        new PromptTemplateSpec.Variable("a", PromptTemplateSpec.TYPE_STRING, false, "a"),
                        new PromptTemplateSpec.Variable("a", PromptTemplateSpec.TYPE_STRING, false, "a dup"),
                        new PromptTemplateSpec.Variable("b", PromptTemplateSpec.TYPE_STRING, false, "b")),
                PromptTemplateSpec.FORMAT_TEXT, null, null, Map.of());
        assertTrue(hasError(PromptTemplateValidator.validate(spec), "PROMPT_VARIABLE_DUPLICATE"));
    }

    @Test
    void invalidVariableTypeIsError() {
        PromptTemplateSpec spec = new PromptTemplateSpec(
                "sys.x", PromptTemplateSpec.SCOPE_SYSTEM, null, "chat_snippet",
                List.of(user("{{a}}")),
                List.of(new PromptTemplateSpec.Variable("a", "unknown", false, "a")),
                PromptTemplateSpec.FORMAT_TEXT, null, null, Map.of());
        assertTrue(hasError(PromptTemplateValidator.validate(spec), "PROMPT_VARIABLE_TYPE_INVALID"));
    }

    @Test
    void invalidPurposeIsError() {
        PromptTemplateSpec spec = new PromptTemplateSpec(
                "sys.x", PromptTemplateSpec.SCOPE_SYSTEM, null, "not_a_purpose",
                List.of(user("hi")), List.of(), PromptTemplateSpec.FORMAT_TEXT, null, null, Map.of());
        assertTrue(hasError(PromptTemplateValidator.validate(spec), "PROMPT_PURPOSE_INVALID"));
    }

    @Test
    void jsonFormatWithoutSchemaIsWarningOnly() {
        PromptTemplateSpec spec = new PromptTemplateSpec(
                "sys.x", PromptTemplateSpec.SCOPE_SYSTEM, null, "extract",
                List.of(user("{{a}}")),
                List.of(new PromptTemplateSpec.Variable("a", PromptTemplateSpec.TYPE_STRING, true, "a")),
                PromptTemplateSpec.FORMAT_JSON, null, null, Map.of("a", "x"));
        List<PromptTemplateValidator.Issue> issues = PromptTemplateValidator.validate(spec);
        assertTrue(hasError(issues, "PROMPT_SCHEMA_MISSING") == false, "缺 Schema 只应是 WARN");
        assertTrue(issues.stream().anyMatch(i -> "PROMPT_SCHEMA_MISSING".equals(i.code())
                && PromptTemplateValidator.SEVERITY_WARN.equals(i.severity())));
        assertTrue(PromptTemplateValidator.ok(issues));
    }

    @Test
    void sampleRenderFailureIsError() {
        // 必填变量无默认值且样例变量缺失 → 样例渲染失败
        PromptTemplateSpec spec = new PromptTemplateSpec(
                "sys.x", PromptTemplateSpec.SCOPE_SYSTEM, null, "chat_snippet",
                List.of(user("{{a}}")),
                List.of(new PromptTemplateSpec.Variable("a", PromptTemplateSpec.TYPE_STRING, true, "a")),
                PromptTemplateSpec.FORMAT_TEXT, null, null, Map.of());
        assertTrue(hasError(PromptTemplateValidator.validate(spec), "PROMPT_SAMPLE_RENDER_FAILED"));
    }

    @Test
    void emptySectionsIsError() {
        PromptTemplateSpec spec = new PromptTemplateSpec(
                "sys.x", PromptTemplateSpec.SCOPE_SYSTEM, null, "chat_snippet",
                List.of(), List.of(), PromptTemplateSpec.FORMAT_TEXT, null, null, Map.of());
        assertTrue(hasError(PromptTemplateValidator.validate(spec), "PROMPT_SECTION_EMPTY"));
    }

    @Test
    void nullSpecIsError() {
        List<PromptTemplateValidator.Issue> issues = PromptTemplateValidator.validate(null);
        assertTrue(hasError(issues, "PROMPT_SPEC_NULL"));
        assertFalse(PromptTemplateValidator.ok(issues));
    }
}
