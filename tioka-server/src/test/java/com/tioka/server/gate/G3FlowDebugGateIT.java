package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M3 G3 闸门骨架（P3-00b）。实现前保持 @Disabled。
* 覆盖：FLW-13a（ADR-043 第 17 节）。
 * 步骤：
 * 1. 调试 Run 写工具只走 prepare
 * 2. 断点与单步可观察
 */
@Tag("gate")
@Disabled("G3 blocked: FLW-13a suspended (ADR-043 Flow domain descoping)")
class G3FlowDebugGateIT {

    @Test
    void pending() {
        // 见类注释步骤；对应包转绿时删除 @Disabled 并实现断言
    }
}
