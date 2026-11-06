package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 闸门 G1：34 号计划 G1 全链路。用例说明见 docs/architecture/construction/M1-05（G1-E2E 第 1〜8 步）。
 *
 * <p>运行环境：GateCluster（3 实例）+ Testcontainers（PostgreSQL、Redis、Keycloak）+ LLM 桩 + MCP 桩（ENG-04b）。
 * 实现者删除 {@code @Disabled} 并补全断言；闸门通过前不得合并对应阶段的发布分支。
 */
@Tag("gate")
@Disabled("骨架：待 M1 全部 P0 项实现后启用（W12）")
class G1EndToEndGateIT {

    /** Keycloak 登录 → 选已发布 Agent → 3 步 MCP 工具调用 → 另一实例 SSE 流式 → 另一实例取消 → 用量计入空间配额 → 审计可查 */
    @Test
    void keycloakLoginPublishedAgentMcpToolsQuotaAudit() {
        fail("待实现：Keycloak 登录 → 选已发布 Agent → 3 步 MCP 工具调用 → 另一实例 SSE 流式 → 另一实例取消 → 用量计入空间配额 → 审计可查");
    }

    /** 无 MCP 授权的 Agent 调用 erp.update：模型收到拒绝结果，审计 tool.invoke DENIED */
    @Test
    void unauthorizedToolDeniedEndToEnd() {
        fail("待实现：无 MCP 授权的 Agent 调用 erp.update：模型收到拒绝结果，审计 tool.invoke DENIED");
    }
}
