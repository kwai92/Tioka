package com.tioka.runtime.api.spi;

/** 变更单条目操作类型（ADR-033）。 */
public enum ChangeOp {
    CREATE,
    UPDATE,
    DELETE,
    SEND,
    PAY
}
