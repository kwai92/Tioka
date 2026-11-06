package com.tioka.runtime.kernel.domain;

/** 用户取消 / 身份失效取消 Turn。 */
public final class CancelledSignal extends RuntimeException {
    private final String reason;

    public CancelledSignal() {
        this(null);
    }

    public CancelledSignal(String reason) {
        super(reason == null || reason.isBlank() ? "CANCELLED" : reason);
        this.reason = reason == null || reason.isBlank() ? null : reason;
    }

    /** 写入 {@code runtime_turn.error_code}；普通 interrupt 为 null。 */
    public String reason() {
        return reason;
    }
}
