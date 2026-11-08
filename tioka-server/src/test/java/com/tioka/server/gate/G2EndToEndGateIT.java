package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 闸门 G2：34 号全链路（M2-00 第 7.1 节 {@code G2EndToEndGateIT}）。
 *
 * <p>运行环境：双实例 + RocketMQ + 飞书开放平台桩 + 录制模型桩。
 * 转绿包（PK-27）删除 {@code @Disabled} 并补全断言。
 */
@Tag("gate")
@Tag("mq")
@Disabled("骨架：待飞书主链与编排闭环就绪后由 PK-27 启用")
class G2EndToEndGateIT {

    /**
     * 步骤（注释契约）：
     * <ol>
     *   <li>飞书桩向实例 1 推送一条 @机器人消息</li>
     *   <li>规则（{@code auto}，{@code agent} 动作）接单</li>
     *   <li>Turn 在实例 2 执行</li>
     *   <li>回执提出 {@code ask}</li>
     *   <li>飞书桩收到审批卡片</li>
     *   <li>重启实例 2</li>
     *   <li>飞书桩向实例 1 回调「批准」</li>
     *   <li>Run 在存活实例上续跑</li>
     *   <li>回执 {@code notice} 发回飞书桩（结果回写）</li>
     * </ol>
     * 断言：只有 1 个工单、1 个 Run、审批卡片只发 1 次、最终消息只发 1 次；
     * Web 审批台上该审批显示「已由某人批准」。
     */
    @Test
    void feishuAskApproveNoticeEndToEnd() {
        fail("待实现：飞书 @机器人 → 规则接单 → 跨实例 Turn → ask 卡片 → 重启 → 批准 → notice 回写");
    }
}
