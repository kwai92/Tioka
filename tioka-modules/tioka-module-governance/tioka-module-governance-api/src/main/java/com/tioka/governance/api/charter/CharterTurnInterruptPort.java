package com.tioka.governance.api.charter;

import java.util.List;

/**
 * 授权书暂停后的连带效果端口（TRU-03 / ADR-032：SUSPENDED 的效果——
 * 运行中的 Turn 在下一步开始前取消）。实现在 runtime-biz（复用 TurnSignalService.interrupt，
 * 取消信号经 Redis SignalBus 跨实例生效）；本模块只声明契约。
 */
public interface CharterTurnInterruptPort {

    /**
     * 取消指定 Agent 版本集合下全部非终态 Turn。
     *
     * @param reason 写入 {@code runtime_turn.error_code}（审计可读，如 {@code charter_suspended}）
     * @return 实际发出取消信号的 Turn 数
     */
    int interruptActiveTurns(List<String> agentVersionIds, String reason);
}
