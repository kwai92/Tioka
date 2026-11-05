package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 闸门 G1：RT-07。用例说明见 docs/architecture/construction/M1-02。
 *
 * <p>运行环境：双实例 + Testcontainers（PostgreSQL、Redis，按需 Keycloak、RocketMQ）+ 录制回放的模型桩（ENG-04）。
 * 实现者删除 {@code @Disabled} 并补全断言；闸门通过前不得合并对应阶段的发布分支。
 */
@Tag("gate")
@Disabled("骨架：待 RT-07 实现后启用")
class G1ApprovalCrossInstanceGateIT {

    /** WRITE 工具在 A 上触发审批挂起，在 B 上批准，Turn 在任一实例续跑并 COMPLETED。 */
    @Test
    void suspendOnAApproveOnBResumeOnC() {
        fail("待实现：WRITE 工具在 A 上触发审批挂起，在 B 上批准，Turn 在任一实例续跑并 COMPLETED");
    }
}
