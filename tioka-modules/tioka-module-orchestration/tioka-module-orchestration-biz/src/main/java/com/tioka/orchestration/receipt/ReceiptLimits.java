package com.tioka.orchestration.receipt;

import java.time.Duration;

/**
 * 回执语义限额（M2-02 / orch-receipt-v1）。
 */
public final class ReceiptLimits {

    /** rewrite_count &lt; 此值时可再开重写 Turn。 */
    public static final int MAX_REWRITE_COUNT = 2;

    /** FAILED Turn 在无副作用时可重建的最大 attempt。 */
    public static final int MAX_ATTEMPT = 3;

    public static final Duration ASK_TIMEOUT_MAX = Duration.ofDays(7);

    public static final int LATER_MAX_DAYS = 90;

    public static final int SUMMARY_MAX_LENGTH = 2000;

    private ReceiptLimits() {
    }
}
