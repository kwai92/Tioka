package com.tioka.runtime.kernel.application;

import com.tioka.system.api.event.MemberRemovedEvent;
import com.tioka.system.api.event.UserDisabledEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * FND-04e：监听 system-api 生命周期事件，取消受影响 Turn。
 */
@Component
public class UserLifecycleTurnListener {
    private final PrincipalTurnRevoker revoker;

    public UserLifecycleTurnListener(PrincipalTurnRevoker revoker) {
        this.revoker = revoker;
    }

    @EventListener
    public void onDisabled(UserDisabledEvent event) {
        revoker.revokeAllForUser(event.userId());
    }

    @EventListener
    public void onMemberRemoved(MemberRemovedEvent event) {
        revoker.revokeInWorkspace(event.userId(), event.workspaceId());
    }
}
