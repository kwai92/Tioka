package com.tioka.system.api.evidence;

/**
 * 证据包平台签名端口（OBS-12 / ADR-075 §5）。
 *
 * <p>实现对「包整体哈希」以平台私钥做 SHA256withRSA 签名；公钥可独立下发，
 * 供法务 / 审计方离线重算各文件哈希、包哈希并验签（不依赖平台在线服务）。
 *
 * <p>私钥保存在 {@code SecretStore}（at-rest 加密），首次使用时生成并持久化；
 * 实现须落在允许调用 {@code SecretStore.get} 的白名单包内。
 */
public interface EvidencePackSigner {

    /** 签名算法标识（当前固定 {@code SHA256withRSA}）。 */
    String algorithm();

    /** 平台公钥（X.509 SubjectPublicKeyInfo 的 PEM 文本），用于离线验签。 */
    String publicKeyPem();

    /** 密钥标识：平台公钥 DER 的 SHA-256 前 16 位十六进制，随签名一并登记以便密钥轮换追溯。 */
    String keyId();

    /**
     * 对给定载荷做平台签名。
     *
     * @param payload 待签名原始字节（约定为「包整体哈希的十六进制串」的 UTF-8 字节）
     * @return 十六进制小写签名
     */
    String sign(byte[] payload);
}
