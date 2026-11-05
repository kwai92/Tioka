package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 闸门 G0：RT-03。用例说明见 docs/architecture/construction/M0-03。
 *
 * <p>运行环境：双实例 + Testcontainers（PostgreSQL、Redis，按需 Keycloak、RocketMQ）+ 录制回放的模型桩（ENG-04）。
 * 实现者删除 {@code @Disabled} 并补全断言；闸门通过前不得合并对应阶段的发布分支。
 */
@Tag("gate")
@Disabled("骨架：待 RT-03 实现后启用")
class G0MultiStepGateIT {

    /** 剧本 three-tools.yaml：3 个 tool_call、3 个 tool_result、1 条最终 message，Turn COMPLETED。 */
    @Test
    void threeToolCallsCompleteAutomatically() {
        fail("待实现：剧本 three-tools.yaml：3 个 tool_call、3 个 tool_result、1 条最终 message，Turn COMPLETED");
    }
}
