package com.tioka.orchestration.receipt;

/**
 * orch_fact.kind 常量（ORC-03 回执回写）。
 */
public final class ReceiptFactKinds {

    public static final String MISSING_RECEIPT = "missing_receipt";
    public static final String RECEIPT_INVALID = "receipt_invalid";
    public static final String LATE_RESULT = "late_result";
    public static final String EMIT_REJECTED = "emit_rejected";
    public static final String RECEIPT_APPLIED = "receipt_applied";
    public static final String RECEIPT_REWRITE = "receipt_rewrite";
    public static final String ATTENTION_OPENED = "attention_opened";
    public static final String TURN_FAILED = "turn_failed";
    public static final String TURN_CANCELLED = "turn_cancelled";

    private ReceiptFactKinds() {
    }
}
