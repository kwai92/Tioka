package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M3 G3 闸门骨架（P3-00b）。实现前保持 @Disabled。
* 覆盖：MH-12、MH-14（ADR-042 第 7 节）。
 * 步骤：
 * 1. 多 Key 凭证选用与轮换
 * 2. 参数覆盖与故障转移时参数接力翻译
 */
@Tag("gate")
@Disabled("P3-00b skeleton — enable when owning package turns green")
class G3CredentialGateIT {

    @Test
    void pending() {
        // 见类注释步骤；对应包转绿时删除 @Disabled 并实现断言
    }
}
