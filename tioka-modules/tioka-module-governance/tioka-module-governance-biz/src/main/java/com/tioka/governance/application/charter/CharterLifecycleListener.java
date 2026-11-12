package com.tioka.governance.application.charter;

import com.tioka.system.api.event.MemberRemovedEvent;
import com.tioka.system.api.event.UserDisabledEvent;
import org.springframework.context.event.EventListener;

/**
 * TRU-03 加速路径（同实例）：进程内监听 M1 身份事件（afterCommit 发布），即时驱动授权书暂停，
 * 不等 outbox 中继。与 MQ 主口径（{@code IdentityCharterConsumer}）双路进入同一状态机，
 * 幂等由状态转移 CAS 保证，不会重复写 SUSPEND 事件。
 */
public class CharterLifecycleListener {

    private final CharterLifecycleService lifecycle;

    public CharterLifecycleListener(CharterLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @EventListener
    public void onDisabled(UserDisabledEvent event) {
        if (event == null || event.userId() == null) {
            return;
        }
        lifecycle.suspendByOwnerDisable(event.userId());
    }

    @EventListener
    public void onMemberRemoved(MemberRemovedEvent event) {
        if (event == null || event.userId() == null) {
            return;
        }
        lifecycle.suspendByMemberRemove(event.workspaceId(), event.userId());
    }
}
