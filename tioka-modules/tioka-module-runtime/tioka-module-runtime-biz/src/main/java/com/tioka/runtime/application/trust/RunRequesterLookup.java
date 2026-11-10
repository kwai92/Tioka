package com.tioka.runtime.application.trust;

/**
 * 编排工单请求人查询（TRU-07 可撤销人之一）。runtime 不读 orch_mandate，
 * 由 orchestration 侧在 M4 提供实现；M3 无实现即跳过该项（登记 M3-C4）。
 */
public interface RunRequesterLookup {

    boolean isRequester(String userId, String orchRunId);
}
