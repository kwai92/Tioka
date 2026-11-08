package com.tioka.framework.security;

/**
 * 空实现：未接入 FND-08 前禁止误用。PK-14a 提供真实 AES-GCM 实现后由
 * {@code @ConditionalOnMissingBean} 替换。
 */
public final class NoopCredentialCipher implements CredentialCipher {

    @Override
    public String encrypt(String plaintext, String credentialId, String ownerId) {
        throw new UnsupportedOperationException("CredentialCipher 尚未实现（FND-08 / PK-14a）");
    }

    @Override
    public String decrypt(String ciphertext, String credentialId, String ownerId) {
        throw new UnsupportedOperationException("CredentialCipher 尚未实现（FND-08 / PK-14a）");
    }

    @Override
    public int currentKeyVersion() {
        return 0;
    }
}
