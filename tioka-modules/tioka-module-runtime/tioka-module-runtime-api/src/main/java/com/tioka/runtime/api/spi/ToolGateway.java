package com.tioka.runtime.api.spi;

import com.tioka.framework.security.Principal;

import java.util.List;
import java.util.Map;

/**
 * 执行面唯一工具入口（ADR-014 D3；CON-01 鉴权 / 限流 / 审计）。
 *
 * <p>{@link ToolCallContext} 只经 {@link ToolCallContexts} 工厂构造（M1-00 F3）。
 *
 * <p>TRU-05（PK-24a）：声明 {@code prepare} / {@code commit}；默认实现抛未支持，由 PK-24b 落地。
 */
public interface ToolGateway {
    List<ToolProvider.ToolDescriptor> listTools(ToolCallContext ctx);

    /**
     * 只读工具：等价于 prepare + commit。写工具在 PK-24b 后应改走 prepare/commit。
     */
    ToolProvider.ToolResult call(ToolCallContext ctx, String toolName, Map<String, Object> args);

    /**
     * 写工具预演：检查与变更单，不改变外部状态。
     */
    default PreparedCall prepare(ToolCallContext ctx, String toolName, Map<String, Object> args) {
        throw new UnsupportedOperationException("ToolGateway.prepare 待 PK-24b 实现");
    }

    /**
     * 按 prepare 结果提交执行。
     */
    default ToolProvider.ToolResult commit(ToolCallContext ctx, String preparedId) {
        throw new UnsupportedOperationException("ToolGateway.commit 待 PK-24b 实现");
    }

    List<Map<String, Object>> asOpenAiTools(ToolCallContext ctx);

    /**
     * 预演结果（ADR-033）。
     */
    record PreparedCall(
            String preparedId,
            String actionClass,
            ChangeSet changeSet,
            PolicyOutcome policy,
            GateDecision gate) {}

    /**
     * 工具调用上下文（取代 SessionToolContext）。
     *
     * @param agentToolConfig 含 {@code mcp}（{@code [{serverId, allowlist[]}]} = McpBinding），由 Runtime 从 Agent 快照注入
     * @param grantId         委派授予 ID（MAS-01）；null 视为根授予尚未签发（仅测试 / 无授予链路，放行 2.5 步）
     */
    record ToolCallContext(Principal principal, String threadId, String turnId, String agentVersionId,
                           String origin, List<String> toolAllowList, Map<String, Object> agentToolConfig,
                           String grantId) {
        /** 兼容旧 8 参口径（仅 spi 内部工厂使用；M3 后 grantId 必经工厂注入）。 */
        public ToolCallContext(Principal principal, String threadId, String turnId, String agentVersionId,
                               String origin, List<String> toolAllowList, Map<String, Object> agentToolConfig) {
            this(principal, threadId, turnId, agentVersionId, origin, toolAllowList, agentToolConfig, null);
        }
    }
}
