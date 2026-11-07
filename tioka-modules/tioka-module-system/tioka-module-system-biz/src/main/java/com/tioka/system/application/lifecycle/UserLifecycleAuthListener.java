package com.tioka.system.application.lifecycle;

import com.tioka.system.api.event.MemberRemovedEvent;
import com.tioka.system.api.event.UserDisabledEvent;
import com.tioka.system.api.event.UserEnabledEvent;
import com.tioka.system.application.auth.PrincipalLoader;
import com.tioka.system.application.auth.TokenService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * FND-04e：禁用时吊销令牌族并清缓存；启用 / 成员移除只清缓存。
 */
@Component
public class UserLifecycleAuthListener {
    private final TokenService tokens;
    private final PrincipalLoader principals;

    public UserLifecycleAuthListener(TokenService tokens, PrincipalLoader principals) {
        this.tokens = tokens;
        this.principals = principals;
    }

    @EventListener
    public void onDisabled(UserDisabledEvent event) {
        long uid = Long.parseLong(event.userId());
        tokens.revokeAll(uid);
        principals.evictUser(uid);
    }

    @EventListener
    public void onEnabled(UserEnabledEvent event) {
        principals.evictUser(Long.parseLong(event.userId()));
    }

    @EventListener
    public void onMemberRemoved(MemberRemovedEvent event) {
        principals.evictUser(Long.parseLong(event.userId()));
    }
}
