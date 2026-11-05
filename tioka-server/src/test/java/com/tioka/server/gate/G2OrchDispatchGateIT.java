package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 闸门 G2：ORC-02、ORC-03。用例说明见 docs/architecture/construction/M2-01、M2-02。
 *
 * <p>运行环境：双实例 + Testcontainers（PostgreSQL、Redis，按需 Keycloak、RocketMQ）+ 录制回放的模型桩（ENG-04）。
 * 实现者删除 {@code @Disabled} 并补全断言；闸门通过前不得合并对应阶段的发布分支。
 */
@Tag("gate")
@Disabled("骨架：待 ORC-02、ORC-03 实现后启用")
class G2OrchDispatchGateIT {

    /** 投递事件 → 规则命中 → Run → Turn → orch.receipt → Run SUCCEEDED；重复投递同一事件不产生第二个 Run。 */
    @Test
    void eventToRunToReceipt() {
        fail("待实现：投递事件 → 规则命中 → Run → Turn → orch.receipt → Run SUCCEEDED；重复投递同一事件不产生第二个 Run");
    }
}
