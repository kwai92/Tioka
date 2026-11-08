package com.tioka.framework.security;

/**
 * 凭证信封加密（FND-08 / ADR-042 / 约束 F19）。
 *
 * <p>明文 + 附加认证数据（凭证 ID + 所属对象 ID）→ 带密钥版本的密文。
 * 不绑定用户令牌表；主密钥轮换的重加密按「表 + 列」经 {@link CredentialReencryptRegistry} 登记。
 *
 * <p>密文约定（实现方遵守）：{@code v{keyVersion}:base64(iv|ciphertext|tag)}（AES-256-GCM）。
 */
public interface CredentialCipher {

    /**
     * @param credentialId 凭证主键或逻辑 ID（写入 AAD）
     * @param ownerId      所属对象 ID，如 userId / bindingId（写入 AAD）
     * @return 带版本前缀的密文
     */
    String encrypt(String plaintext, String credentialId, String ownerId);

    /**
     * @throws IllegalArgumentException AAD 不匹配、密文损坏或密钥版本未知
     */
    String decrypt(String ciphertext, String credentialId, String ownerId);

    /** 当前用于加密的主密钥版本号。 */
    int currentKeyVersion();
}
