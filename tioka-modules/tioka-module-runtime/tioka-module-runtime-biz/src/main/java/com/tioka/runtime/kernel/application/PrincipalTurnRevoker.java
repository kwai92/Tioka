package com.tioka.runtime.kernel.application;

import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * FND-04e：用户禁用 / 成员移除后，立即取消该身份下未终态 Turn（不再等 60s Principal 重载）。
 */
@Service
public class PrincipalTurnRevoker {
    private static final Logger log = LoggerFactory.getLogger(PrincipalTurnRevoker.class);

    private final TurnRepository turns;
    private final TurnSignalService signals;
    private final PrincipalHolder principals;

    public PrincipalTurnRevoker(TurnRepository turns,
                                TurnSignalService signals,
                                PrincipalHolder principals) {
        this.turns = turns;
        this.signals = signals;
        this.principals = principals;
    }

    public void revokeAllForUser(String userId) {
        revoke(userId, null);
    }

    public void revokeInWorkspace(String userId, String workspaceId) {
        revoke(userId, workspaceId);
    }

    private void revoke(String userId, String workspaceId) {
        // 跨空间扫描：以 SYSTEM 绕过 workspace 行级过滤
        principals.runAs(SystemPrincipals.system(), () -> {
            List<RuntimeTurnDO> active = turns.findActiveByPrincipal(userId, workspaceId);
            for (RuntimeTurnDO t : active) {
                try {
                    signals.interrupt(t.getId(), TurnSignalService.REASON_PRINCIPAL_REVOKED);
                } catch (RuntimeException e) {
                    log.warn("principal revoke cancel failed turnId={}: {}", t.getId(), e.toString());
                }
            }
            return null;
        });
    }
}
