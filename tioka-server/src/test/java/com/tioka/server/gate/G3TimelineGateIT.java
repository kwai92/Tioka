package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M3 G3 闸门骨架（P3-00b）。实现前保持 @Disabled。
* 覆盖：OBS-06〜09（ADR-075）。
 * 步骤：
 * 1. 一份合同从推送到通知在一条时间线
 * 2. 外发记录可列出；导出审计留痕
 */
@Tag("gate")
@Disabled("P3-00b skeleton — enable when owning package turns green")
class G3TimelineGateIT {

    @Test
    void pending() {
        // 见类注释步骤；对应包转绿时删除 @Disabled 并实现断言
    }
}
