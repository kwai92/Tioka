package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M3 G3 闸门骨架（P3-00b）。实现前保持 @Disabled。
* 覆盖：RT-12、MAS-01〜03（M3-01）。
 * 步骤：
 * 1. 主管委派子 Agent，授予收窄
 * 2. 越权工具被拒并记链
 * 3. 子任务回收
 */
@Tag("gate")
@Disabled("P3-00b skeleton — enable when owning package turns green")
class G3DelegationGateIT {

    @Test
    void pending() {
        // 见类注释步骤；对应包转绿时删除 @Disabled 并实现断言
    }
}
