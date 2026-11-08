package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M3 G3 闸门骨架（P3-00b）。实现前保持 @Disabled。
* 覆盖：DOC、PRS-00、SCH、ONT、FLW-08、NTF、CON-07a（M3-03 第 17 节）。
 * 步骤：
 * 1. 文档入库 → 解析 → Schema 校验
 * 2. 本体草稿确认与 map 节点
 * 3. 通知投递与业务连接器
 */
@Tag("gate")
@Disabled("P3-00b skeleton — enable when owning package turns green")
class G3OntologyPlatformGateIT {

    @Test
    void pending() {
        // 见类注释步骤；对应包转绿时删除 @Disabled 并实现断言
    }
}
