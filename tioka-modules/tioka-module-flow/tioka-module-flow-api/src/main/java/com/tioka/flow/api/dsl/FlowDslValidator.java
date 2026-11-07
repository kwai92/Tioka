package com.tioka.flow.api.dsl;

/**
 * Flow DSL 发布校验（ADR-043 §3.7）。
 */
public interface FlowDslValidator {

    ValidationResult validate(FlowDslDocument document, FlowPublishContext context);
}
