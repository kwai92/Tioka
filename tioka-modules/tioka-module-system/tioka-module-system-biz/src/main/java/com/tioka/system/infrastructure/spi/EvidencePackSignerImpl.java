package com.tioka.system.infrastructure.spi;

import com.tioka.system.api.evidence.EvidencePackSigner;
import com.tioka.system.api.spi.SecretStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 证据包平台签名（OBS-12 / ADR-075 §5）：SHA256withRSA，私钥 at-rest 存 {@link SecretStore}。
 *
 * <p>首次使用时生成 RSA 密钥对并持久化（命名空间 {@value #NS}）；之后复用。公钥随包下发，
 * 供法务 / 审计方离线重算各文件哈希、包整体哈希并验签，无需在线服务。
 *
 * <p>落在 {@code com.tioka.system.infrastructure.spi}（ADR-014 D5 调用 {@code SecretStore.get} 的白名单包）。
 */
@Component
public class EvidencePackSignerImpl implements EvidencePackSigner {
    private static final Logger log = LoggerFactory.getLogger(EvidencePackSignerImpl.class);

    static final String NS = "evidence-pack";
    static final String KEY_PRIVATE = "signing-key-private-pem";
    static final String KEY_PUBLIC = "signing-key-public-pem";
    private static final String ALG = "SHA256withRSA";
    private static final int RSA_BITS = 2048;

    private final SecretStore secrets;

    /** 惰性加载/首用生成；{@code volatile} 供读路径无锁快取。 */
    private volatile KeyPair keyPair;
    private volatile String keyId;

    public EvidencePackSignerImpl(SecretStore secrets) {
        this.secrets = secrets;
    }

    @Override
    public String algorithm() {
        return ALG;
    }

    @Override
    public String publicKeyPem() {
        return toPem("PUBLIC KEY", keyPair().getPublic().getEncoded());
    }

    @Override
    public String keyId() {
        keyPair();
        return keyId;
    }

    @Override
    public String sign(byte[] payload) {
        try {
            Signature signer = Signature.getInstance(ALG);
            signer.initSign(keyPair().getPrivate());
            signer.update(payload == null ? new byte[0] : payload);
            return HexFormat.of().formatHex(signer.sign());
        } catch (Exception e) {
            throw new IllegalStateException("证据包签名失败: " + e.getMessage(), e);
        }
    }

    /** 读私钥（缺失则生成并持久化）；首用生成需互斥，故方法级同步（基础设施层，不受应用层禁 synchronized 约束）。 */
    private synchronized KeyPair keyPair() {
        if (keyPair != null) {
            return keyPair;
        }
        try {
            String privPem = secrets.get(NS, KEY_PRIVATE);
            String pubPem = secrets.get(NS, KEY_PUBLIC);
            if (privPem != null && pubPem != null) {
                PrivateKey priv = KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(pemBody(privPem)));
                PublicKey pub = KeyFactory.getInstance("RSA")
                        .generatePublic(new X509EncodedKeySpec(pemBody(pubPem)));
                keyPair = new KeyPair(pub, priv);
            } else {
                KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
                gen.initialize(RSA_BITS);
                KeyPair generated = gen.generateKeyPair();
                secrets.put(NS, KEY_PRIVATE, toPem("PRIVATE KEY", generated.getPrivate().getEncoded()));
                secrets.put(NS, KEY_PUBLIC, toPem("PUBLIC KEY", generated.getPublic().getEncoded()));
                keyPair = generated;
                log.info("evidence pack signing key generated (algorithm={}, keyId={})",
                        ALG, keyId(generated.getPublic()));
            }
            keyId = keyId(keyPair.getPublic());
            return keyPair;
        } catch (Exception e) {
            throw new IllegalStateException("证据包签名密钥装载失败: " + e.getMessage(), e);
        }
    }

    /** keyId = 公钥 DER 的 SHA-256 前 16 位十六进制（ADR-075 §5）。 */
    private static String keyId(PublicKey pub) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(pub.getEncoded());
        return HexFormat.of().formatHex(digest).substring(0, 16);
    }

    private static String toPem(String label, byte[] encoded) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(encoded);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    }

    static byte[] pemBody(String pem) {
        String body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
