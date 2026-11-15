package com.tioka.model.application.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.governance.api.config.ConfigVersionApi.ValidationIssue;
import com.tioka.model.api.prompt.PromptTemplateSpec;
import com.tioka.model.api.versioning.ModelHubSpecs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AST-07（ADR-041 §2）：Prompt 模板校验器接入 GOV-12 发布校验的适配测试。
 */
class PromptTemplateSpecValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final PromptTemplateSpecValidator validator = new PromptTemplateSpecValidator(MAPPER);

    @Test
    void supportsOnlyPromptTemplateType() {
        assertTrue(validator.supports(ModelHubSpecs.OT_PROMPT_TEMPLATE));
        assertFalse(validator.supports(ModelHubSpecs.OT_PROVIDER));
        assertFalse(validator.supports("unknown"));
    }

    @Test
    void validSpecYieldsNoIssues() {
        PromptTemplateSpec spec = new PromptTemplateSpec(
                "sys.ok", PromptTemplateSpec.SCOPE_SYSTEM, null, "summarize",
                List.of(new PromptTemplateSpec.Section(PromptTemplateSpec.ROLE_USER, "正文：{{content}}")),
                List.of(new PromptTemplateSpec.Variable("content", PromptTemplateSpec.TYPE_STRING, true,
                        "正文")),
                PromptTemplateSpec.FORMAT_TEXT, null, "sys.chat",
                Map.of("content", "（正文）"));
        List<ValidationIssue> issues = validator.validate(ModelHubSpecs.OT_PROMPT_TEMPLATE, "prmtpl_1",
                spec.toJson(MAPPER));
        assertTrue(issues.isEmpty(), "合法 spec 不应产生问题: " + issues);
    }

    @Test
    void invalidSpecYieldsErrors() {
        PromptTemplateSpec spec = new PromptTemplateSpec(
                "plain", PromptTemplateSpec.SCOPE_SYSTEM, null, "chat_snippet",
                List.of(new PromptTemplateSpec.Section(PromptTemplateSpec.ROLE_USER, "你好 {{missing}}")),
                List.of(),
                PromptTemplateSpec.FORMAT_TEXT, null, null, Map.of());
        List<ValidationIssue> issues = validator.validate(ModelHubSpecs.OT_PROMPT_TEMPLATE, "prmtpl_2",
                spec.toJson(MAPPER));
        assertFalse(issues.isEmpty());
        assertTrue(issues.stream().anyMatch(i -> "PROMPT_NAME_SCOPE_MISMATCH".equals(i.code())));
        assertTrue(issues.stream().anyMatch(i -> "PROMPT_PLACEHOLDER_UNDEFINED".equals(i.code())));
    }

    @Test
    void nonObjectSpecYieldsSpecNull() {
        List<ValidationIssue> issues = validator.validate(ModelHubSpecs.OT_PROMPT_TEMPLATE, "prmtpl_3",
                MAPPER.createArrayNode());
        assertEquals(1, issues.size());
        assertEquals("PROMPT_SPEC_NULL", issues.get(0).code());
    }
}
