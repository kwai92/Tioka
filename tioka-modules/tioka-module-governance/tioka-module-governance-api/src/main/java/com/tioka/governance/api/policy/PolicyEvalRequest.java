package com.tioka.governance.api.policy;

import java.util.Map;

/**
 * 策略求值输入（M3-05：主体、动作、资源、上下文）。
 */
public record PolicyEvalRequest(
        String principalRef,
        String action,
        String resourceType,
        String resourceId,
        Map<String, Object> resourceAttrs,
        Map<String, Object> context
) {}
