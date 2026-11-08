package com.tioka.governance.api.charter;

/**
 * 授权书状态变化事件写入（ADR-032；TRU-01 建表与端口，TRU-03 接线生命周期）。
 * ID 前缀 {@code chev}。
 */
public interface CharterEventWriter {

    void append(AppendCommand command);

    record AppendCommand(
            String workspaceId,
            String agentId,
            CharterEventKind kind,
            String reason,
            String fromOwner,
            String toOwner,
            String actor
    ) {}
}
