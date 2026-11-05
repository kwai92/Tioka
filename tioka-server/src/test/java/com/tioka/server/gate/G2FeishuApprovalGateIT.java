package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 闸门 G2：CON-03。用例说明见 docs/architecture/construction/M2-03。
 *
 * <p>运行环境：双实例 + Testcontainers（PostgreSQL、Redis，按需 Keycloak、RocketMQ）+ 录制回放的模型桩（ENG-04）。
 * 实现者删除 {@code @Disabled} 并补全断言；闸门通过前不得合并对应阶段的发布分支。
 */
@Tag("gate")
@Disabled("骨架：待 CON-03 实现后启用")
class G2FeishuApprovalGateIT {

    /** 编排 ask → 飞书审批卡片 → 签名按钮回调批准 → Run 续跑 → Web 审批台状态同步。 */
    @Test
    void cardClickApprovesAndSyncs() {
        fail("待实现：编排 ask → 飞书审批卡片 → 签名按钮回调批准 → Run 续跑 → Web 审批台状态同步");
    }
}
