package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 闸门 G1：RT-06。用例说明见 docs/architecture/construction/M1-02。
 *
 * <p>运行环境：双实例 + Testcontainers（PostgreSQL、Redis，按需 Keycloak、RocketMQ）+ 录制回放的模型桩（ENG-04）。
 * 实现者删除 {@code @Disabled} 并补全断言；闸门通过前不得合并对应阶段的发布分支。
 */
@Tag("gate")
@Disabled("骨架：待 RT-06 实现后启用")
class G1RecoveryGateIT {

    /** Turn 执行工具时关闭实例 A：B 接管，补「结果丢失」tool_result，最终 COMPLETED，recover_count=1，客户端收到 item.discarded。 */
    @Test
    void turnTakenOverAfterCrash() {
        fail("待实现：Turn 执行工具时关闭实例 A：B 接管，补「结果丢失」tool_result，最终 COMPLETED，recover_count=1，客户端收到 item.discarded");
    }
}
