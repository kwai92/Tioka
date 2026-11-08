package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M3 G3 闸门骨架（P3-00b）。实现前保持 @Disabled。
* 覆盖：FLW-09、FLW-11a；cancelPropagation 需 FLW-04（ADR-043 第 17 节）。
 * 步骤：
 * 1. 新引擎启动/取消/崩溃恢复
 * 2. 核心节点跑通
 * 3. 取消沿子节点传播
 */
@Tag("gate")
@Disabled("P3-00b skeleton — enable when owning package turns green")
class G3FlowEngineGateIT {

    @Test
    void pending() {
        // 见类注释步骤；对应包转绿时删除 @Disabled 并实现断言
    }
}
