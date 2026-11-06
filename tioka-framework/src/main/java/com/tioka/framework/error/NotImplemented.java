package com.tioka.framework.error;

public final class NotImplemented {
    private NotImplemented() {}

    public static BizException of(String capability, String adr) {
        return new BizException(ErrorCode.NOT_IMPLEMENTED, capability + " 尚未实现，见 " + adr);
    }
}
