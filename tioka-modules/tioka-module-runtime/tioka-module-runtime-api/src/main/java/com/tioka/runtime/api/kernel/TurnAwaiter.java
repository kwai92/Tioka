package com.tioka.runtime.api.kernel;

import java.time.Duration;
import java.util.Optional;

/**
 * 给需要同步结果的调用方等待 Turn（RT-08，施工设计 M1-02）。
 *
 * <p>只用于 Flow Agent 节点等确实需要同步的场景；Chat、IM 必须异步订阅事件。
 */
public interface TurnAwaiter {

    /** 等到终态或 WAITING_*；超时返回当前状态，不取消 Turn。 */
    RuntimeApi.TurnView await(String turnId, Duration timeout);

    /** 本 Turn 最后一条 assistant message 的文本。 */
    Optional<String> finalText(String turnId);
}
