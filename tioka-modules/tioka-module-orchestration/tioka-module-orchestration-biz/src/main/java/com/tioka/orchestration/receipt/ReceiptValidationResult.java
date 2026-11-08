package com.tioka.orchestration.receipt;

import com.tioka.orchestration.api.engine.Receipt;

import java.util.List;

/**
 * 回执整体校验结果。
 *
 * @param receipt   解析后的回执；校验失败时仍可能非空
 * @param errors    阻断性错误（触发重写）
 * @param rejected  非阻断拒绝项（如 emit 不在白名单）
 */
public record ReceiptValidationResult(
        Receipt receipt,
        List<String> errors,
        List<RejectedEmit> rejected) {

    public boolean ok() {
        return errors == null || errors.isEmpty();
    }

    public record RejectedEmit(String type, String reason) {
    }
}
