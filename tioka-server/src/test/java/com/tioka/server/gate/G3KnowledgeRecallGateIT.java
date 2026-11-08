package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M3 G3 闸门骨架（P3-00b）。实现前保持 @Disabled。
* 覆盖：KNW-03〜05、KNW-07、KNW-11、KNW-12、KNW-14（M3-06 第 3 节）。
 * 步骤：
 * 1. 评测集文档 READY
 * 2. Agent 提问带引用；Recall@5 不低于基线
 * 3. 无权限用户检索不到受限文档
 */
@Tag("gate")
@Disabled("P3-00b skeleton — enable when owning package turns green")
class G3KnowledgeRecallGateIT {

    @Test
    void pending() {
        // 见类注释步骤；对应包转绿时删除 @Disabled 并实现断言
    }
}
