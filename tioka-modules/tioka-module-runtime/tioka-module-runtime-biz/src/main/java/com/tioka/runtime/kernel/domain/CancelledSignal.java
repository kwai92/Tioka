package com.tioka.runtime.kernel.domain;

/** 用户取消 Turn。 */
public final class CancelledSignal extends RuntimeException {
    public CancelledSignal() {
        super("CANCELLED");
    }
}
