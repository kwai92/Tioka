package com.tioka.model.application.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.governance.api.config.ConfigSpecValidator;
import com.tioka.governance.api.config.ConfigVersionApi.ValidationIssue;
import com.tioka.model.api.prompt.PromptTemplateSpec;
import com.tioka.model.api.prompt.PromptTemplateValidator;
import com.tioka.model.api.versioning.ModelHubSpecs;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * AST-07（ADR-041 §2 / §4）：把 Prompt 模板专属校验注册进 GOV-12 发布校验。
 *
 * <p>{@code DefaultConfigVersionService.validate} 只做基线；本组件对 {@code prm_template}
 * 追加字段合法性、占位符与变量定义一致性、样例变量渲染自检。
 */
@Component
public class PromptTemplateSpecValidator implements ConfigSpecValidator {

    private final ObjectMapper mapper;

    public PromptTemplateSpecValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean supports(String objectType) {
        return ModelHubSpecs.OT_PROMPT_TEMPLATE.equals(objectType);
    }

    @Override
    public List<ValidationIssue> validate(String objectType, String objectId, JsonNode spec) {
        List<ValidationIssue> out = new ArrayList<>();
        PromptTemplateSpec parsed = PromptTemplateSpec.fromJson(mapper, spec);
        if (parsed == null) {
            out.add(new ValidationIssue("PROMPT_SPEC_NULL", PromptTemplateValidator.SEVERITY_ERROR,
                    "模板 spec 为空或不是 JSON 对象", "/"));
            return List.copyOf(out);
        }
        for (PromptTemplateValidator.Issue i : PromptTemplateValidator.validate(parsed)) {
            out.add(new ValidationIssue(i.code(), i.severity(), i.message(), i.path()));
        }
        return List.copyOf(out);
    }
}
