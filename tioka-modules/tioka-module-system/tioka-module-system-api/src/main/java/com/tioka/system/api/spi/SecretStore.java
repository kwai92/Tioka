package com.tioka.system.api.spi;

/** 密钥存储（at-rest 加密）。 */
public interface SecretStore {
    String put(String namespace, String key, String plaintext);
    String get(String namespace, String key);
    void delete(String namespace, String key);
}
