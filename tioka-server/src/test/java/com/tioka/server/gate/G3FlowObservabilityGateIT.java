package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M3 G3 闸门骨架（P3-00b）。实现前保持 @Disabled。
* 覆盖：FLW-14a、FLW-14b（ADR-043 第 17 节）。
 * 步骤：
 * 1. Flow Run 成本与节点 span
 * 2. 观测指标可抓取
 */
@Tag("gate")
@Disabled("G3 blocked: FLW-14a/FLW-14b suspended")
class G3FlowObservabilityGateIT {

    @Test
    void pending() {
        // 见类注释步骤；对应包转绿时删除 @Disabled 并实现断言
    }
}
