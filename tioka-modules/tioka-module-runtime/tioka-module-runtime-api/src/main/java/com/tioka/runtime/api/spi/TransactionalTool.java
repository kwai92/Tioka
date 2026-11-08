package com.tioka.runtime.api.spi;

/**
 * 业务系统事务型工具（ADR-033）。预演 / 提交 / 补偿钩子；实现留给连接器与 FakeErp（PK-24c）。
 */
public interface TransactionalTool extends ToolProvider {

    /** 不改变外部状态，可重复调用。 */
    ChangeSet preview(ToolInvocation inv);

    /** 按已批准变更单执行；idempotencyKey 通常为 change_set item id。 */
    ChangeSet.ApplyReceipt apply(ToolInvocation inv, ChangeSet approved, String idempotencyKey);

    /** REVERSIBLE / COMPENSABLE 必须实现；幂等。 */
    ChangeSet.CompensationResult compensate(ChangeSet.ApplyReceipt receipt, String idempotencyKey);
}
