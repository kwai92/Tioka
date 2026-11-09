package com.tioka.runtime.api.spi;

import com.tioka.framework.security.Principal;

import java.util.List;
import java.util.Map;

/**
 * {@link ToolGateway.ToolCallContext} 唯一工厂（M1-00 F3）。
 * 调用方禁止直接 {@code new ToolCallContext(...)}；M3 扩展字段只改本工厂。
 */
public final class ToolCallContexts {
    private ToolCallContexts() {}

    /** 旧 8 参语义：grantId = null（根授予尚未签发，仅测试 / 无授予链路用）。 */
    public static ToolGateway.ToolCallContext forTurn(Principal principal,
                                                     String threadId,
                                                     String turnId,
                                                     String agentVersionId,
                                                     String origin,
                                                     List<String> toolAllowList,
                                                     Map<String, Object> agentToolConfig) {
        return forTurn(principal, threadId, turnId, agentVersionId, origin,
                toolAllowList, agentToolConfig, null);
    }

    /** M3 MAS-01：带委派授予 ID 的完整上下文。 */
    public static ToolGateway.ToolCallContext forTurn(Principal principal,
                                                     String threadId,
                                                     String turnId,
                                                     String agentVersionId,
                                                     String origin,
                                                     List<String> toolAllowList,
                                                     Map<String, Object> agentToolConfig,
                                                     String grantId) {
        return new ToolGateway.ToolCallContext(
                principal,
                threadId,
                turnId,
                agentVersionId,
                origin == null ? "" : origin,
                toolAllowList == null ? List.of() : List.copyOf(toolAllowList),
                agentToolConfig == null ? Map.of() : Map.copyOf(agentToolConfig),
                grantId);
    }
}
