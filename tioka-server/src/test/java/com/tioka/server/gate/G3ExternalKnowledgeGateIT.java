package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M3 G3 闸门骨架（P3-00b）。实现前保持 @Disabled。
* 覆盖：KNW-16、KNW-17a（ADR-057）。
 * 步骤：
 * 1. 自有 + 外部混合检索
 * 2. 标签超限不出网
 * 3. 飞书用户身份下两位用户结果不同
 */
@Tag("gate")
@Disabled("G3 blocked: KNW-16/KNW-17a not started")
class G3ExternalKnowledgeGateIT {

    @Test
    void pending() {
        // 见类注释步骤；对应包转绿时删除 @Disabled 并实现断言
    }
}
