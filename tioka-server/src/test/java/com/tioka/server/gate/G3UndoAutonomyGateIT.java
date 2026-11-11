package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M3 G3 闸门骨架（P3-00b）。实现前保持 @Disabled。
* 覆盖：TRU-07〜10（M3-05 第 10 节）。
 * 步骤：
 * 1. L2 下 5 步写操作后撤销，外部状态复原
 * 2. 动作类别降级并通知负责人
 * 3. 熔断后全部类别 L1；补偿失败停后续并人审
 */
@Tag("gate")
@Disabled("G3 blocked: TRU-08/TRU-10 not started")
class G3UndoAutonomyGateIT {

    @Test
    void pending() {
        // 见类注释步骤；对应包转绿时删除 @Disabled 并实现断言
    }
}
