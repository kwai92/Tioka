package com.tioka.runtime.api.context;

/**
 * 上下文四区（ADR-048 第 1 节；P3-00b / K1a 段提供者注册点）。
 */
public enum ContextZone {
    /** 稳定前缀 */
    A_STABLE_PREFIX,
    /** 会话快照 */
    B_THREAD_SNAPSHOT,
    /** 历史 */
    C_HISTORY,
    /** 本轮尾部 */
    D_TURN_TAIL
}
