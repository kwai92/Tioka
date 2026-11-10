package com.tioka.runtime.application.trust;

import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.api.spec.Charter;
import com.tioka.runtime.infrastructure.TrustUndoWindowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;

/**
 * 撤销期解析默认实现（M3-05 §5.2）：授权书动作类别扩展字段 {@code undoWindowHours}
 * → 空间默认值（V202 {@code trust_undo_window}）→ 24h。
 */
@org.springframework.stereotype.Component
public class DefaultUndoWindowResolver implements UndoWindowResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultUndoWindowResolver.class);
    private static final Duration DEFAULT_WINDOW = Duration.ofHours(24);

    private final ObjectProvider<AgentSnapshotApi> agentSnapshots;
    private final TrustUndoWindowRepository windows;

    public DefaultUndoWindowResolver(ObjectProvider<AgentSnapshotApi> agentSnapshots,
                                     TrustUndoWindowRepository windows) {
        this.agentSnapshots = agentSnapshots;
        this.windows = windows;
    }

    @Override
    public Duration windowFor(String workspaceId, String agentVersionId, String actionClass) {
        Long perClass = charterWindow(agentVersionId, actionClass);
        if (perClass != null && perClass > 0) {
            return Duration.ofHours(perClass);
        }
        Integer ws = windows.windowHours(workspaceId);
        if (ws != null && ws > 0) {
            return Duration.ofHours(ws);
        }
        return DEFAULT_WINDOW;
    }

    private Long charterWindow(String agentVersionId, String actionClass) {
        AgentSnapshotApi snapshots = agentSnapshots.getIfAvailable();
        if (snapshots == null || agentVersionId == null || agentVersionId.isBlank()) {
            return null;
        }
        try {
            AgentSnapshotApi.AgentSnapshot snap = snapshots.get(agentVersionId);
            AgentSpec spec = snap.spec();
            Charter charter = spec == null ? null : Charter.parse(spec.charter());
            if (charter == null || actionClass == null || actionClass.isBlank()) {
                return null;
            }
            for (Charter.ActionClass ac : charter.actionClasses()) {
                if (actionClass.equals(ac.code())) {
                    return ac.undoWindowHours();
                }
            }
            return null;
        } catch (RuntimeException e) {
            log.debug("撤销期授权书解析失败 version={}: {}", agentVersionId, e.toString());
            return null;
        }
    }
}
