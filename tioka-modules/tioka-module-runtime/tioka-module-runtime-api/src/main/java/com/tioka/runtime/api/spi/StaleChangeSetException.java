package com.tioka.runtime.api.spi;

/**
 * 提交前指纹漂移（ADR-033）。实现方在 {@link TransactionalTool#apply} 中抛出。
 */
public class StaleChangeSetException extends RuntimeException {
    public StaleChangeSetException(String message) {
        super(message);
    }
}
