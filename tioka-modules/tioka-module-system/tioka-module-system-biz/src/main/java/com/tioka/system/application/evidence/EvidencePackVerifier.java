package com.tioka.system.application.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 证据包离线校验（OBS-12 / ADR-075 §5）。
 *
 * <p>供法务 / 审计方在无平台服务的前提下：重算各文件 SHA-256、重算包整体哈希、并以平台公钥验签。
 * 做法：逐文件比对内容摘要 → 比对包整体哈希 → 用公钥校验签名；任一步不符即判失败。
 */
public final class EvidencePackVerifier {

    private EvidencePackVerifier() {}

    /** 单文件校验结果。 */
    public record FileCheck(String name, boolean ok, String declared, String actual) {}

    /** 整体校验结果。 */
    public record VerifyReport(
            boolean ok,
            boolean packageHashMatch,
            boolean signatureValid,
            String declaredPackageHash,
            String recomputedPackageHash,
            List<FileCheck> files) {}

    /**
     * 校验证据包。
     *
     * @param bundle       证据包 JSON 字节
     * @param publicKeyPem 平台公钥（X.509 PEM）
     * @param mapper       复用宿主 ObjectMapper（避免生产代码 new ObjectMapper，ENG-05）
     */
    public static VerifyReport verify(byte[] bundle, String publicKeyPem, ObjectMapper mapper) {
        try {
            JsonNode root = mapper.readTree(bundle);
            String declaredPackageHash = text(root.get("packageHash"));
            String signatureHex = text(root.get("signature"));

            SortedMap<String, String> declared = new TreeMap<>();
            List<FileCheck> checks = new ArrayList<>();
            JsonNode files = root.get("files");
            boolean allFilesOk = true;
            if (files != null && files.isArray()) {
                for (JsonNode f : files) {
                    String name = text(f.get("name"));
                    String sha = text(f.get("sha256"));
                    String content = text(f.get("content"));
                    String actual = sha256Hex(content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8));
                    boolean ok = sha != null && sha.equals(actual);
                    allFilesOk &= ok;
                    checks.add(new FileCheck(name, ok, sha, actual));
                    if (name != null) {
                        declared.put(name, sha);
                    }
                }
            }

            String recomputed = packageHash(declared);
            boolean packageHashMatch = recomputed.equals(declaredPackageHash);
            boolean signatureValid = verifySignature(recomputed, signatureHex, publicKeyPem);
            return new VerifyReport(allFilesOk && packageHashMatch && signatureValid,
                    packageHashMatch, signatureValid, declaredPackageHash, recomputed, checks);
        } catch (Exception e) {
            return new VerifyReport(false, false, false, null, null, List.of());
        }
    }

    /**
     * 包整体哈希：各文件「名称:内容哈希」按名称升序、以 {@code '\n'} 连接并补尾换行后的 SHA-256。
     * 与 {@link EvidencePackService} 打包口径一致，供独立重算。
     */
    static String packageHash(SortedMap<String, String> nameToHash) {
        StringBuilder sb = new StringBuilder();
        for (var e : nameToHash.entrySet()) {
            sb.append(e.getKey()).append(':').append(e.getValue() == null ? "" : e.getValue()).append('\n');
        }
        return sha256Hex(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static boolean verifySignature(String packageHash, String signatureHex, String publicKeyPem) {
        if (packageHash == null || signatureHex == null || signatureHex.isBlank() || publicKeyPem == null) {
            return false;
        }
        try {
            PublicKey key = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(pemBody(publicKeyPem)));
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update(packageHash.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(HexFormat.of().parseHex(signatureHex.trim()));
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] pemBody(String pem) {
        return Base64.getDecoder().decode(pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", ""));
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
