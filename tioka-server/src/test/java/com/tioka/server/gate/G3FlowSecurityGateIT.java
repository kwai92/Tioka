package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M3 G3 闸门骨架（P3-00b）。实现前保持 @Disabled。
* 覆盖：FLW-15（ADR-043 第 17 节）。
 * 步骤：
 * 1. Flow 发布引用检查
 * 2. 运行身份矩阵与无权引用被拒
 */
@Tag("gate")
@Disabled("G3 blocked: FLW-15 suspended")
class G3FlowSecurityGateIT {

    @Test
    void pending() {
        // 见类注释步骤；对应包转绿时删除 @Disabled 并实现断言
    }
}
