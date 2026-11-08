package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M3 G3 闸门骨架（P3-00b）。实现前保持 @Disabled。
* 覆盖：SCH-06〜10（ADR-066）。
 * 步骤：
 * 1. 合同 Schema 最终失败率与异常逃逸断言
 * 2. 未连 failed 端口的 Flow 发布被拒
 */
@Tag("gate")
@Disabled("P3-00b skeleton — enable when owning package turns green")
class G3StructuredOutputGateIT {

    @Test
    void pending() {
        // 见类注释步骤；对应包转绿时删除 @Disabled 并实现断言
    }
}
