package com.tioka.infra.application.plugin;

import com.tioka.infra.api.plugin.PluginDescriptor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 签名覆盖「清单规范串 + 入口类字节」的 SHA-256，再用 HMAC 密钥（可多把，F34）。
 */
public final class PluginSignatureVerifier {

    private final List<byte[]> keys;
    private final boolean required;

    public PluginSignatureVerifier(List<String> hexKeys, boolean required) {
        this.required = required;
        this.keys = hexKeys == null ? List.of() : hexKeys.stream()
                .filter(k -> k != null && !k.isBlank())
                .map(k -> HexFormat.of().parseHex(k))
                .toList();
    }

    public void verify(PluginDescriptor descriptor, byte[] entryClassBytes) {
        if (!required) {
            return;
        }
        String signatureHex = descriptor.signature();
        if (signatureHex == null || signatureHex.isBlank()) {
            throw new PluginLoadException("signature required");
        }
        if (keys.isEmpty()) {
            throw new PluginLoadException("no verification keys configured");
        }
        byte[] payload = payload(descriptor, entryClassBytes);
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signatureHex);
        } catch (IllegalArgumentException e) {
            throw new PluginLoadException("invalid signature encoding");
        }
        for (byte[] key : keys) {
            if (MessageDigest.isEqual(hmacSha256(key, payload), provided)) {
                return;
            }
        }
        throw new PluginLoadException("signature mismatch");
    }

    public static String sign(PluginDescriptor descriptor, byte[] entryClassBytes, String hexKey) {
        byte[] key = HexFormat.of().parseHex(hexKey);
        return HexFormat.of().formatHex(hmacSha256(key, payload(descriptor, entryClassBytes)));
    }

    private static byte[] payload(PluginDescriptor descriptor, byte[] entryClassBytes) {
        List<String> permissions = descriptor.permissions() == null
                ? List.of()
                : new ArrayList<>(descriptor.permissions());
        permissions.sort(String::compareTo);
        String canonical = descriptor.pluginKey() + '\n'
                + descriptor.version() + '\n'
                + descriptor.spiType() + '\n'
                + descriptor.spiVersion() + '\n'
                + String.join(",", permissions) + '\n'
                + descriptor.entryClass() + '\n';
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(canonical.getBytes(StandardCharsets.UTF_8));
            digest.update(entryClassBytes == null ? new byte[0] : entryClassBytes);
            return digest.digest();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
