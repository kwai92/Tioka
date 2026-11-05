package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 闸门 G2：CON-02。用例说明见 docs/architecture/construction/M2-03。
 *
 * <p>运行环境：双实例 + Testcontainers（PostgreSQL、Redis，按需 Keycloak、RocketMQ）+ 录制回放的模型桩（ENG-04）。
 * 实现者删除 {@code @Disabled} 并补全断言；闸门通过前不得合并对应阶段的发布分支。
 */
@Tag("gate")
@Disabled("骨架：待 CON-02 实现后启用")
class G2FeishuChatGateIT {

    /** 签名加密的飞书 @机器人 事件 → 3 秒内应答 → 会话映射 → Turn 完成 → 卡片被更新为最终答案；同话题第二条消息进入同一会话。 */
    @Test
    void mentionBotStartsConversation() {
        fail("待实现：签名加密的飞书 @机器人 事件 → 3 秒内应答 → 会话映射 → Turn 完成 → 卡片被更新为最终答案；同话题第二条消息进入同一会话");
    }
}
