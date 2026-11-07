package com.tioka.model.infrastructure.provider;

/**
 * Provider HTTP / 传输层失败。供 MH-02 判定是否可切换 binding；
 * MH-03 用 {@link #retryAfterMs()} 做同 binding 退避。
 */
public final class ProviderHttpException extends Exception {
    private final int statusCode;
    private final boolean switchable;
    private final Long retryAfterMs;

    public ProviderHttpException(int statusCode, String message, boolean switchable) {
        this(statusCode, message, switchable, null);
    }

    public ProviderHttpException(int statusCode, String message, boolean switchable, Long retryAfterMs) {
        super(message == null ? ("provider status " + statusCode) : message);
        this.statusCode = statusCode;
        this.switchable = switchable;
        this.retryAfterMs = retryAfterMs;
    }

    public int statusCode() {
        return statusCode;
    }

    public boolean switchable() {
        return switchable;
    }

    /** HTTP 429 的 Retry-After（毫秒）；其它情况为 null。 */
    public Long retryAfterMs() {
        return retryAfterMs;
    }

    /** 连接失败、超时等：无 HTTP 状态，可切换。 */
    public static ProviderHttpException transport(String message, Throwable cause) {
        ProviderHttpException ex = new ProviderHttpException(0, message, true, null);
        if (cause != null) {
            ex.initCause(cause);
        }
        return ex;
    }

    public static boolean isSwitchableStatus(int status) {
        if (status == 429) {
            return true;
        }
        return status >= 500 && status <= 599;
    }

    public static boolean isNonSwitchableStatus(int status) {
        return status == 400 || status == 401 || status == 403 || status == 404;
    }

    /** 解析 Retry-After：秒数或 HTTP-date；解析失败返回 null。 */
    public static Long parseRetryAfterMs(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String t = header.trim();
        try {
            long sec = Long.parseLong(t);
            if (sec < 0) {
                return null;
            }
            return sec * 1000L;
        } catch (NumberFormatException ignored) {
            // HTTP-date 较少见；MH-03 仅保证秒数路径
            return null;
        }
    }
}
