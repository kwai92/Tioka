package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M3 G3 闸门骨架（P3-00b）。实现前保持 @Disabled。
* 覆盖：LSE、BIZ（含过渡权限）（M3-04 第 6 节）。
 * 步骤：
 * 1. 区域负责人只看到本区域合同
 * 2. 审查 Agent 跑通要点与 Finding
 */
@Tag("gate")
@Disabled("G3 blocked: LSE-05/LSE-07/LSE-08 and BIZ-01..08 + transitional region permission not started")
class G3LeaseReviewGateIT {

    @Test
    void pending() {
        // 见类注释步骤；对应包转绿时删除 @Disabled 并实现断言
    }
}
