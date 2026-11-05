package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 闸门 G1：FND-04、CON-01、GOV-02。用例说明见 docs/architecture/construction/M1-01、M1-04。
 *
 * <p>运行环境：双实例 + Testcontainers（PostgreSQL、Redis，按需 Keycloak、RocketMQ）+ 录制回放的模型桩（ENG-04）。
 * 实现者删除 {@code @Disabled} 并补全断言；闸门通过前不得合并对应阶段的发布分支。
 */
@Tag("gate")
@Disabled("骨架：待 FND-04、CON-01、GOV-02 实现后启用")
class G1PermissionGateIT {

    /** 普通成员修改他人 Agent 返回 403，审计有 DENIED 记录。 */
    @Test
    void memberCannotEditOthersAgent() {
        fail("待实现：普通成员修改他人 Agent 返回 403，审计有 DENIED 记录");
    }

    /** 无 MCP 授权的工具调用被拒，模型收到拒绝结果，审计有 tool.invoke DENIED。 */
    @Test
    void unauthorizedToolCallDeniedAndAudited() {
        fail("待实现：无 MCP 授权的工具调用被拒，模型收到拒绝结果，审计有 tool.invoke DENIED");
    }
}
