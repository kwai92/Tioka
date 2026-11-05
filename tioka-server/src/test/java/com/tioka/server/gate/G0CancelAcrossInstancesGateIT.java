package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 闸门 G0：RT-05。用例说明见 docs/architecture/construction/M0-03。
 *
 * <p>运行环境：双实例 + Testcontainers（PostgreSQL、Redis，按需 Keycloak、RocketMQ）+ 录制回放的模型桩（ENG-04）。
 * 实现者删除 {@code @Disabled} 并补全断言；闸门通过前不得合并对应阶段的发布分支。
 */
@Tag("gate")
@Disabled("骨架：待 RT-05 实现后启用")
class G0CancelAcrossInstancesGateIT {

    /** Turn 在 A 上流式输出，向 B 发 interrupt；1 秒内 A 停止输出，状态 CANCELLED，已流出文本保留。 */
    @Test
    void cancelOnBStopsTurnOnA() {
        fail("待实现：Turn 在 A 上流式输出，向 B 发 interrupt；1 秒内 A 停止输出，状态 CANCELLED，已流出文本保留");
    }
}
