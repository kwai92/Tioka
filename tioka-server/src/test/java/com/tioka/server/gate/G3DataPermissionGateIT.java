package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M3 G3 闸门骨架（P3-00b）。实现前保持 @Disabled。
* 覆盖：DAT-01〜03（M3-02）。
 * 步骤：
 * 1. 数据集行列策略
 * 2. 标签传播与去向许可
 * 3. 按标签选模
 */
@Tag("gate")
@Disabled("P3-00b skeleton — enable when owning package turns green")
class G3DataPermissionGateIT {

    @Test
    void pending() {
        // 见类注释步骤；对应包转绿时删除 @Disabled 并实现断言
    }
}
