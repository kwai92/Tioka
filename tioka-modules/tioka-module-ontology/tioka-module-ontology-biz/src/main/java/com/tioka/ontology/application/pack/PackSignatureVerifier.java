package com.tioka.ontology.application.pack;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * ONT-05 领域包清单验签（M3-03 §10 加载顺序第 1 步）。
 *
 * <p>算法：SHA256withRSA（M3-03 §10 未指定算法，按批次冻结决策采用 RSA 公钥验签），
 * 公钥经配置注入（HEX 编码的 X.509 SubjectPublicKeyInfo）。签名覆盖「清单规范串 + 入口类字节」，
 * 规范串口径与 FND-12 {@code PluginSignatureVerifier} 一致（pluginKey/version/spiType/spiVersion/
 * permissions 排序/entryClass），保证与宿主侧签名可互算。
 *
 * <p><b>缺省关闭</b>（signatureRequired=false，批次偏差登记）：与 FND-12 宿主验签双轨并存，
 * 公钥下发流程落地后由配置打开。
 */
public final class PackSignatureVerifier {

    private final List<PublicKey> keys;
    private final boolean required;

    public PackSignatureVerifier(List<String> hexPublicKeys, boolean required) {
        this.required = required;
        List<PublicKey> parsed = new ArrayList<>();
        if (hexPublicKeys != null) {
            for (String hex : hexPublicKeys) {
                if (hex == null || hex.isBlank()) {
                    continue;
                }
                try {
                    byte[] encoded = hex.trim().startsWith("-----")
                            ? Base64.getMimeDecoder().decode(pemBody(hex))
                            : HexFormat.of().parseHex(hex.trim());
                    parsed.add(KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded)));
                } catch (Exception e) {
                    throw new IllegalArgumentException("pack 验签公钥解析失败: " + e.getMessage(), e);
                }
            }
        }
        this.keys = List.copyOf(parsed);
    }

    private static String pemBody(String pem) {
        return pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
    }

    public boolean required() {
        return required;
    }

    /** 验签失败抛 {@link PackSignatureException}；未启用验签时直接通过。 */
    public void verify(DomainPackManifest manifest, byte[] entryClassBytes) {
        if (!required) {
            return;
        }
        if (keys.isEmpty()) {
            throw new PackSignatureException("no verification keys configured");
        }
        String signatureHex = manifest.signature();
        if (signatureHex == null || signatureHex.isBlank()) {
            throw new PackSignatureException("signature required");
        }
        byte[] payload = payload(manifest, entryClassBytes);
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signatureHex.trim());
        } catch (IllegalArgumentException e) {
            throw new PackSignatureException("invalid signature encoding");
        }
        for (PublicKey key : keys) {
            try {
                Signature verifier = Signature.getInstance("SHA256withRSA");
                verifier.initVerify(key);
                verifier.update(payload);
                if (verifier.verify(provided)) {
                    return;
                }
            } catch (Exception e) {
                // 尝试下一把公钥
            }
        }
        throw new PackSignatureException("signature mismatch");
    }

    /** 与 FND-12 PluginSignatureVerifier 相同的规范串（便于同一密钥对双轨签发）。 */
    public static byte[] payload(DomainPackManifest manifest, byte[] entryClassBytes) {
        List<String> permissions = manifest.permissions() == null
                ? List.of()
                : new ArrayList<>(manifest.permissions());
        permissions.sort(String::compareTo);
        String canonical = manifest.resolvedPackKey() + '\n'
                + manifest.version() + '\n'
                + manifest.spiType() + '\n'
                + manifest.spiVersion() + '\n'
                + String.join(",", permissions) + '\n'
                + manifest.entryClass() + '\n';
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(canonical.getBytes(StandardCharsets.UTF_8));
            digest.update(entryClassBytes == null ? new byte[0] : entryClassBytes);
            return digest.digest();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 验签失败。 */
    public static final class PackSignatureException extends RuntimeException {
        public PackSignatureException(String message) {
            super(message);
        }
    }
}
