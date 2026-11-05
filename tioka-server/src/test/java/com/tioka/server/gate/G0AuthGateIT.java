package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 闸门 G0：FND-01、FND-03。用例说明见 docs/architecture/construction/M0-02。
 *
 * <p>运行环境：双实例 + Testcontainers（PostgreSQL、Redis，按需 Keycloak、RocketMQ）+ 录制回放的模型桩（ENG-04）。
 * 实现者删除 {@code @Disabled} 并补全断言；闸门通过前不得合并对应阶段的发布分支。
 */
@Tag("gate")
@Disabled("骨架：待 FND-01、FND-03 实现后启用")
class G0AuthGateIT {

    /** 枚举所有 /api/** 映射（排除 @PublicApi），无令牌请求全部 401；?token= 查询参数无效。 */
    @Test
    void allApiRequireLogin() {
        fail("待实现：枚举所有 /api/** 映射（排除 @PublicApi），无令牌请求全部 401；?token= 查询参数无效");
    }

    /** 两个用户在不同空间各建会话、Agent、知识库；互相列表不可见，详情 404。 */
    @Test
    void usersCannotSeeEachOther() {
        fail("待实现：两个用户在不同空间各建会话、Agent、知识库；互相列表不可见，详情 404");
    }
}
