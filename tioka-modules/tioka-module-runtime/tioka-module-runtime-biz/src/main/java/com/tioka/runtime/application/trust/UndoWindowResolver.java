package com.tioka.runtime.application.trust;

import java.time.Duration;

/**
 * 撤销期解析（TRU-07 / M3-05 §5.2）：授权书动作类别扩展字段 {@code undoWindowHours}
 * 优先，其次空间级默认值（V202 {@code trust_undo_window}），缺省 24 小时。
 * {@code markApplied} 经本接口取撤销期写 {@code undo_deadline}。
 */
public interface UndoWindowResolver {

    Duration windowFor(String workspaceId, String agentVersionId, String actionClass);
}
