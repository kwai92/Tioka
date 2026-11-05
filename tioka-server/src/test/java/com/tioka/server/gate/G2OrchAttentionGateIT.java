package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 闸门 G2：ORC-04、HCI-05。用例说明见 docs/architecture/construction/M2-02、M2-04。
 *
 * <p>运行环境：双实例 + Testcontainers（PostgreSQL、Redis，按需 Keycloak、RocketMQ）+ 录制回放的模型桩（ENG-04）。
 * 实现者删除 {@code @Disabled} 并补全断言；闸门通过前不得合并对应阶段的发布分支。
 */
@Tag("gate")
@Disabled("骨架：待 ORC-04、HCI-05 实现后启用")
class G2OrchAttentionGateIT {

    /** 回执 ask → 审批台出现待办 → 批准 → 同一 thread 新 Turn → Run SUCCEEDED。 */
    @Test
    void askThenApproveResumes() {
        fail("待实现：回执 ask → 审批台出现待办 → 批准 → 同一 thread 新 Turn → Run SUCCEEDED");
    }
}
