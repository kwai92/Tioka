package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M3 G3 闸门骨架（P3-00b）。实现前保持 @Disabled。
* 覆盖：GOV-07、TRU-01〜03（M3-05 第 10 节）。
 * 步骤：
 * 1. 授权书额度内自动 / 超额转审批
 * 2. 禁区拒绝；四眼生效
 * 3. DRYRUN 命中仍执行但审计本应拒绝
 * 4. 负责人禁用后新 Turn 423
 */
@Tag("gate")
@Disabled("G3 blocked: TRU-03 (owner invalidation auto-pause and handover) not started")
class G3CharterGateIT {

    @Test
    void pending() {
        // 见类注释步骤；对应包转绿时删除 @Disabled 并实现断言
    }
}
