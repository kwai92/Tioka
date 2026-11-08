package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M3 G3 闸门骨架（P3-00b）。实现前保持 @Disabled。
* 覆盖：GOV-12、MH-08〜10、GOV-13、AST-07（ADR-041 第 10 节）。
 * 步骤（转绿时实现）：
 * 1. 发布配置版本并跨双实例刷新投影
 * 2. 模型别名/路由/健康与告警规则联动
 * 3. Prompt 模板发布后 Agent 可钉版本引用
 */
@Tag("gate")
@Disabled("P3-00b skeleton — enable when owning package turns green")
class G3ConfigVersionGateIT {

    @Test
    void pending() {
        // 见类注释步骤；对应包转绿时删除 @Disabled 并实现断言
    }
}
