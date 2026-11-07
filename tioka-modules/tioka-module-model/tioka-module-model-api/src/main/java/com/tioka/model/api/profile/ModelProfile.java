package com.tioka.model.api.profile;

/**
 * 模型档案中与结构化输出相关的切片（SCH-02）。完整档案字段随 MH 深化补齐。
 */
public record ModelProfile(
        String modelRef,
        StructuredOutputCapability structuredOutput
) {
    public ModelProfile {
        if (modelRef == null || modelRef.isBlank()) {
            throw new IllegalArgumentException("modelRef required");
        }
        if (structuredOutput == null) {
            throw new IllegalArgumentException("structuredOutput required");
        }
    }

    /** ADR-037 三档便捷构造。 */
    public static ModelProfile legacy(String modelRef, String structuredOutput) {
        return new ModelProfile(modelRef, StructuredOutputCapability.legacy(structuredOutput));
    }
}
