package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 闸门 G1：GOV-03、MH-01、MH-02。用例说明见 docs/architecture/construction/M1-03。
 *
 * <p>运行环境：双实例 + Testcontainers（PostgreSQL、Redis，按需 Keycloak、RocketMQ）+ 录制回放的模型桩（ENG-04）。
 * 实现者删除 {@code @Disabled} 并补全断言；闸门通过前不得合并对应阶段的发布分支。
 */
@Tag("gate")
@Disabled("骨架：待 GOV-03、MH-01、MH-02 实现后启用")
class G1QuotaGateIT {

    /** 空间 token 配额用尽后下一次模型调用 429 QUOTA_EXCEEDED。 */
    @Test
    void tokenQuotaExceededRejectsNext() {
        fail("待实现：空间 token 配额用尽后下一次模型调用 429 QUOTA_EXCEEDED");
    }

    /** 主模型流式中途 503：前端收到 item.discarded，最终文本只来自备用模型。 */
    @Test
    void failoverMidStream() {
        fail("待实现：主模型流式中途 503：前端收到 item.discarded，最终文本只来自备用模型");
    }
}
