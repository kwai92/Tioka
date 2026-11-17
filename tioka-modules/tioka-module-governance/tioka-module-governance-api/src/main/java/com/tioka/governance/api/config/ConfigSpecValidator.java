package com.tioka.governance.api.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.tioka.governance.api.config.ConfigVersionApi.ValidationIssue;

import java.util.List;

/**
 * 类型专属配置校验扩展点（ADR-041 §2 / GOV-12）。
 *
 * <p>框架 {@code validate} 只做基线（草稿存在、spec 为对象或数组）；对象归属方
 * （MH-08 的 Model Hub、AST-07 的 Prompt 模板等）以 Spring Bean 形式注册本接口，
 * 在发布前追加专属检查。未注册任何实现时既有行为不变。
 */
public interface ConfigSpecValidator {

    /** 是否负责该 objectType。 */
    boolean supports(String objectType);

    /** 对该 objectType 的 spec 执行专属校验；返回空列表表示通过。 */
    List<ValidationIssue> validate(String objectType, String objectId, JsonNode spec);
}
