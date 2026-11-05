package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 闸门 G1：FND-05。用例说明见 docs/architecture/construction/M1-01。
 *
 * <p>运行环境：双实例 + Testcontainers（PostgreSQL、Redis，按需 Keycloak、RocketMQ）+ 录制回放的模型桩（ENG-04）。
 * 实现者删除 {@code @Disabled} 并补全断言；闸门通过前不得合并对应阶段的发布分支。
 */
@Tag("gate")
@Disabled("骨架：待 FND-05 实现后启用")
class G1OidcGateIT {

    /** Keycloak 用户经授权码流程登录，按组映射到角色；从组移除后再次登录角色消失。 */
    @Test
    void keycloakUserLogsInWithMappedRole() {
        fail("待实现：Keycloak 用户经授权码流程登录，按组映射到角色；从组移除后再次登录角色消失");
    }
}
