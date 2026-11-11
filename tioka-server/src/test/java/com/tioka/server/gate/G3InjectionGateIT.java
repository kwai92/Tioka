package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M3 G3 闸门骨架（P3-00b）。实现前保持 @Disabled。
* 覆盖：GRD-00、GRD-08a（ADR-065）。
 * 步骤：
 * 1. 10 份注入样本有后果攻击成功率 = 0
 * 2. 结论篡改漏检率 = 0；Turn 污点置位
 */
@Tag("gate")
@Disabled("G3 blocked: GRD-00/GRD-08a not started")
class G3InjectionGateIT {

    @Test
    void pending() {
        // 见类注释步骤；对应包转绿时删除 @Disabled 并实现断言
    }
}
