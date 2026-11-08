package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 闸门 G2：ORC-02、ORC-03、ORC-06 重启韧性（M2-00 第 7.1 节）。
 *
 * <p>运行环境：双实例 + Testcontainers（PostgreSQL、Redis、RocketMQ）+ 飞书桩（ENG-04c）。
 * 转绿包（PK-20）删除 {@code @Disabled} 并补全断言。
 */
@Tag("gate")
@Tag("mq")
@Disabled("骨架：待 ORC-02/03/06 与重启能力就绪后由 PK-20 启用")
class G2RestartGateIT {

    /**
     * 步骤（注释契约，实现时按序落地）：
     * <ol>
     *   <li>派出后：ingest → Run DISPATCHED/RUNNING，重启执行实例，任务完成且不重复</li>
     *   <li>审批等待中：Run WAITING_ATTENTION，重启两侧实例，批准后续跑至 SUCCEEDED，同一 subject 仅 1 个 Run</li>
     *   <li>定时到期前：创建 ACTIVE timer，重启执行实例，到期后 timer DONE 且只触发一次</li>
     * </ol>
     */
    @Test
    void restartDuringDispatchAttentionAndTimer() {
        fail("待实现：派出后 / 审批等待中 / 定时到期前分别重启执行实例，任务完成且不重复");
    }
}
