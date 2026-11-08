package com.tioka.runtime.api.pipeline;

/**
 * 管线步骤裁决。步骤之间只经 {@link ToolPipelineContext} 传值。
 */
public enum ToolPipelineVerdict {
    CONTINUE,
    DENY,
    ESCALATE
}
