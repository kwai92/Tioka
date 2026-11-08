package com.tioka.orchestration.receipt;

import com.tioka.orchestration.api.engine.Receipt;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;

import java.util.List;

/**
 * 回执生效入参（48-CS：业务入参 ≥3 收敛为对象）。
 */
public record ReceiptApplyCommand(
        OrchRunDO run,
        Receipt receipt,
        List<ReceiptValidationResult.RejectedEmit> rejected,
        String tellPolicy) {
}
