package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M3 G3 闸门骨架（P3-00b）。实现前保持 @Disabled。
* 覆盖：FLW-06、DAT-04（ADR-043 第 17 节）。
 * 步骤：
 * 1. Flow 四种发布形态
 * 2. 授予透传到 Flow Run
 */
@Tag("gate")
@Disabled("G3 blocked: FLW-06 suspended; DAT-04 Flow part pending FLW-04/06")
class G3FlowPublishGateIT {

    @Test
    void pending() {
        // 见类注释步骤；对应包转绿时删除 @Disabled 并实现断言
    }
}
