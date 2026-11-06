package com.tioka.model.infrastructure.provider;

/**
 * Provider HTTP / 传输层失败。供 MH-02 判定是否可切换 binding。
 */
public final class ProviderHttpException extends Exception {
    private final int statusCode;
    private final boolean switchable;

    public ProviderHttpException(int statusCode, String message, boolean switchable) {
        super(message == null ? ("provider status " + statusCode) : message);
        this.statusCode = statusCode;
        this.switchable = switchable;
    }

    public int statusCode() {
        return statusCode;
    }

    public boolean switchable() {
        return switchable;
    }

    /** 连接失败、超时等：无 HTTP 状态，可切换。 */
    public static ProviderHttpException transport(String message, Throwable cause) {
        ProviderHttpException ex = new ProviderHttpException(0, message, true);
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
}
