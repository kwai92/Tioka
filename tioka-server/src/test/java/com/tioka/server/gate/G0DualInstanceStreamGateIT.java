package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 闸门 G0：RT-04。用例说明见 docs/architecture/construction/M0-03。
 *
 * <p>运行环境：双实例 + Testcontainers（PostgreSQL、Redis，按需 Keycloak、RocketMQ）+ 录制回放的模型桩（ENG-04）。
 * 实现者删除 {@code @Disabled} 并补全断言；闸门通过前不得合并对应阶段的发布分支。
 */
@Tag("gate")
@Disabled("骨架：待 RT-04 实现后启用")
class G0DualInstanceStreamGateIT {

    /** 实例 A 执行 Turn，客户端连实例 B 订阅 /events，收到完整事件序列。 */
    @Test
    void subscribeOnOtherInstance() {
        fail("待实现：实例 A 执行 Turn，客户端连实例 B 订阅 /events，收到完整事件序列");
    }

    /** 收到 {seq}.{i} 后断开，改连另一实例并带 Last-Event-ID，无重复、无缺失。 */
    @Test
    void resumeWithoutDuplicates() {
        fail("待实现：收到 {seq}.{i} 后断开，改连另一实例并带 Last-Event-ID，无重复、无缺失");
    }
}
