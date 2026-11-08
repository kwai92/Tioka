package com.tioka.connector.infrastructure.feishu;

import com.tioka.framework.security.FeishuEventCrypto;

/**
 * 飞书事件订阅验签解密门面（委托 {@link FeishuEventCrypto}）。
 */
public final class FeishuCrypto {

    public static final long MAX_SKEW_SECONDS = FeishuEventCrypto.MAX_SKEW_SECONDS;

    private FeishuCrypto() {
    }

    public static String sign(String encryptKey, String timestamp, String nonce, String rawBody) {
        return FeishuEventCrypto.sign(encryptKey, timestamp, nonce, rawBody);
    }

    public static void verify(String encryptKey, String timestamp, String nonce, String signature, String rawBody) {
        FeishuEventCrypto.verify(encryptKey, timestamp, nonce, signature, rawBody);
    }

    public static String decrypt(String encryptKey, String encryptBase64) {
        return FeishuEventCrypto.decrypt(encryptKey, encryptBase64);
    }

    public static String verifyAndDecrypt(String encryptKey,
                                          String timestamp,
                                          String nonce,
                                          String signature,
                                          String rawBody,
                                          String encryptFieldBase64) {
        return FeishuEventCrypto.verifyAndDecrypt(
                encryptKey, timestamp, nonce, signature, rawBody, encryptFieldBase64);
    }
}
