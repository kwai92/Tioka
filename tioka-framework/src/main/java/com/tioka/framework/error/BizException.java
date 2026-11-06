package com.tioka.framework.error;

public class BizException extends RuntimeException {
    private final ErrorCode errorCode;
    private final Object data;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.data = null;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.data = null;
    }

    public BizException(ErrorCode errorCode, String message, Object data) {
        super(message);
        this.errorCode = errorCode;
        this.data = data;
    }

    public ErrorCode getErrorCode() { return errorCode; }

    public Object getData() { return data; }
}
