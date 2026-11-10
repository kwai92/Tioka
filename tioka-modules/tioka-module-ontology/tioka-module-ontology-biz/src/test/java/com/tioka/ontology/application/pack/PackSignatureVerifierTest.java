package com.tioka.ontology.application.pack;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 领域包清单验签（ONT-05，M3-03 §10 加载第 1 步）：SHA256withRSA、
 * 签名覆盖「清单规范串 + 入口类字节」、多公钥、缺省关闭（required=false 直接通过）。
 */
class PackSignatureVerifierTest {

    private static final byte[] ENTRY_BYTES = "fake-entry-class-bytes".getBytes();

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private static String sign(KeyPair pair, DomainPackManifest manifest, byte[] entryBytes) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(PackSignatureVerifier.payload(manifest, entryBytes));
        return HexFormat.of().formatHex(signer.sign());
    }

    private static String hexPublicKey(KeyPair pair) {
        return HexFormat.of().formatHex(pair.getPublic().getEncoded());
    }

    private static DomainPackManifest manifest(String signature) {
        return new DomainPackManifest("lease-pack", null, "1.0.0", "DOMAIN_PACK", "1",
                List.of("cpu"), "com.demo.LeasePack", signature, List.of(), List.of(), null);
    }

    @Test
    void 正确签名通过() throws Exception {
        KeyPair pair = rsa();
        DomainPackManifest signed = manifest(sign(pair, manifest(null), ENTRY_BYTES));
        new PackSignatureVerifier(List.of(hexPublicKey(pair)), true).verify(signed, ENTRY_BYTES);
    }

    @Test
    void 权限乱序不影响规范串() throws Exception {
        // 签发与验签用同一 manifest（permissions 内部排序），签名口径与顺序无关
        KeyPair pair = rsa();
        DomainPackManifest signed = manifest(sign(pair, manifest(null), ENTRY_BYTES));
        assertDoesNotThrow(() -> new PackSignatureVerifier(List.of(hexPublicKey(pair)), true)
                .verify(signed, ENTRY_BYTES));
    }

    @Test
    void 篡改清单或字节拒收() throws Exception {
        KeyPair pair = rsa();
        DomainPackManifest signed = manifest(sign(pair, manifest(null), ENTRY_BYTES));
        // 改版本号
        DomainPackManifest tampered = new DomainPackManifest("lease-pack", null, "2.0.0", "DOMAIN_PACK", "1",
                List.of("cpu"), "com.demo.LeasePack", signed.signature(), List.of(), List.of(), null);
        PackSignatureVerifier verifier = new PackSignatureVerifier(List.of(hexPublicKey(pair)), true);
        PackSignatureVerifier.PackSignatureException e = assertThrows(
                PackSignatureVerifier.PackSignatureException.class,
                () -> verifier.verify(tampered, ENTRY_BYTES));
        assertEquals("signature mismatch", e.getMessage());
        // 改入口类字节
        assertThrows(PackSignatureVerifier.PackSignatureException.class,
                () -> verifier.verify(signed, "tampered-bytes".getBytes()));
    }

    @Test
    void 非签名密钥拒收() throws Exception {
        KeyPair signerPair = rsa();
        KeyPair otherPair = rsa();
        DomainPackManifest signed = manifest(sign(signerPair, manifest(null), ENTRY_BYTES));
        assertThrows(PackSignatureVerifier.PackSignatureException.class,
                () -> new PackSignatureVerifier(List.of(hexPublicKey(otherPair)), true).verify(signed, ENTRY_BYTES));
    }

    @Test
    void 多公钥任一命中即通过() throws Exception {
        KeyPair key1 = rsa();
        KeyPair key2 = rsa();
        DomainPackManifest signed = manifest(sign(key2, manifest(null), ENTRY_BYTES));
        assertDoesNotThrow(() -> new PackSignatureVerifier(List.of(hexPublicKey(key1), hexPublicKey(key2)), true)
                .verify(signed, ENTRY_BYTES));
    }

    @Test
    void 缺签名与缺公钥报错() throws Exception {
        KeyPair pair = rsa();
        PackSignatureVerifier verifier = new PackSignatureVerifier(List.of(hexPublicKey(pair)), true);
        PackSignatureVerifier.PackSignatureException e = assertThrows(
                PackSignatureVerifier.PackSignatureException.class,
                () -> verifier.verify(manifest(null), ENTRY_BYTES));
        assertEquals("signature required", e.getMessage());
        PackSignatureVerifier noKeys = new PackSignatureVerifier(List.of(), true);
        PackSignatureVerifier.PackSignatureException e2 = assertThrows(
                PackSignatureVerifier.PackSignatureException.class,
                () -> noKeys.verify(manifest("ab"), ENTRY_BYTES));
        assertEquals("no verification keys configured", e2.getMessage());
        // 非法签名编码
        assertThrows(PackSignatureVerifier.PackSignatureException.class,
                () -> verifier.verify(manifest("zz-not-hex"), ENTRY_BYTES));
    }

    @Test
    void 缺省关闭直接通过() {
        // required=false：无签名 / 假签名都不拦（批次偏差：与 FND-12 双轨并存，公钥下发后打开）
        PackSignatureVerifier verifier = new PackSignatureVerifier(List.of(), false);
        assertDoesNotThrow(() -> verifier.verify(manifest("bogus"), ENTRY_BYTES));
        assertDoesNotThrow(() -> verifier.verify(manifest(null), ENTRY_BYTES));
    }

    @Test
    void pem公钥可用() throws Exception {
        KeyPair pair = rsa();
        String base64 = java.util.Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(pair.getPublic().getEncoded());
        String pem = "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";
        DomainPackManifest signed = manifest(sign(pair, manifest(null), ENTRY_BYTES));
        assertDoesNotThrow(() -> new PackSignatureVerifier(List.of(pem), true).verify(signed, ENTRY_BYTES));
    }

    @Test
    void 非法公钥构造报错() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new PackSignatureVerifier(List.of("not-hex"), true));
        assertTrue(e.getMessage().contains("公钥解析失败"));
    }

    @Test
    void 规范串覆盖清单与入口字节() throws Exception {
        KeyPair pair = rsa();
        byte[] payload1 = PackSignatureVerifier.payload(manifest(null), ENTRY_BYTES);
        byte[] payload2 = PackSignatureVerifier.payload(manifest(null), "other-bytes".getBytes());
        assertNotEquals(HexFormat.of().formatHex(payload1), HexFormat.of().formatHex(payload2));
    }
}
