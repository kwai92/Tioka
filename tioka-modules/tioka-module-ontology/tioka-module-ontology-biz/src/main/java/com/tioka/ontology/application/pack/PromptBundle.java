package com.tioka.ontology.application.pack;

import java.util.Map;

/**
 * @model.build 各步提示词；按步骤与对象类型索引。
 */
public record PromptBundle(Map<String, String> byStepAndType) {

    public PromptBundle {
        if (byStepAndType == null) {
            byStepAndType = Map.of();
        }
    }

    public static PromptBundle empty() {
        return new PromptBundle(Map.of());
    }
}
