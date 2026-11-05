package com.tioka.agent.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SubAgentPolicy {
    private final int maxDepth = 3;

    public void assertDelegable(List<String> allowlist, String targetAgentId, int depth) {
        if (depth >= maxDepth) {
            throw new BizException(ErrorCode.FORBIDDEN, "SubAgent 深度超限");
        }
        if (allowlist != null && !allowlist.isEmpty() && !allowlist.contains(targetAgentId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "目标 Agent 不在可委派列表");
        }
    }
}
