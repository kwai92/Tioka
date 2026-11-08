package com.tioka.connector.api.sdk;

/**
 * 标记为可重试的连接器错误（限流钩子与 runSync 退避会识别本类型与 IOException）。
 */
public class RetryableConnectorException extends RuntimeException {

    public RetryableConnectorException(String message) {
        super(message);
    }

    public RetryableConnectorException(String message, Throwable cause) {
        super(message, cause);
    }
}
