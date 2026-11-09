package com.tioka.connector.application.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * FND-08：OAuth {@code state} 编解码（Base64url JSON + HMAC-SHA256 签名）。
 *
 * <p>载荷含 {@code userId / provider / returnTo / nonce}；签名密钥由既有主密钥
 * {@code tioka.credential.master-key} 派生（SHA-256(master|"oauth-state")，与
 * {@code AesGcmCredentialCipher} 同源，缺省开发主密钥仅限非生产）。
 */
@Component
public class OAuthStateCodec {

    private static final Logger log = LoggerFactory.getLogger(OAuthStateCodec.class);
    private static final Duration MAX_AGE = Duration.ofMinutes(10);

    private final ObjectMapper mapper;
    private final byte[] hmacKey;

    public OAuthStateCodec(ObjectMapper mapper,
                           @Value("${tioka.credential.master-key:}") String masterKey) {
        this.mapper = mapper;
        String key = masterKey == null ? "" : masterKey.trim();
        if (key.isEmpty()) {
            key = com.tioka.framework.security.AesGcmCredentialCipher.DEV_DEFAULT_MASTER_KEY;
            log.warn("OAuthStateCodec 使用内置开发主密钥派生 state 签名；请配置 tioka.credential.master-key");
        }
        this.hmacKey = hmacKey(key);
    }

    public String encode(String userId, String provider, String returnTo) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("provider", provider);
        payload.put("returnTo", returnTo);
        payload.put("nonce", String.valueOf(Instant.now().toEpochMilli()));
        String body = base64Url(json(payload));
        return body + "." + base64Url(hmac(body));
    }

    /** 验签 + 解码；非法 / 过期 / 篡改一律空。 */
    public Optional<StatePayload> decode(String state) {
        if (state == null || state.isBlank()) {
            return Optional.empty();
        }
        int sep = state.indexOf('.');
        if (sep <= 0 || sep == state.length() - 1) {
            return Optional.empty();
        }
        String body = state.substring(0, sep);
        byte[] provided;
        try {
            provided = Base64.getUrlDecoder().decode(state.substring(sep + 1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(hmac(body), provided)) {
            return Optional.empty();
        }
        try {
            StatePayload p = mapper.readValue(Base64.getUrlDecoder().decode(body), StatePayload.class);
            if (p.userId() == null || p.provider() == null || p.returnTo() == null) {
                return Optional.empty();
            }
            long ageMs = Instant.now().toEpochMilli() - Long.parseLong(p.nonce());
            if (ageMs < 0 || ageMs > MAX_AGE.toMillis()) {
                return Optional.empty();
            }
            return Optional.of(p);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public record StatePayload(String userId, String provider, String returnTo, String nonce) {
    }

    /* ---------- 工具 ---------- */

    private byte[] json(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsBytes(payload);
        } catch (Exception e) {
            throw new IllegalStateException("state JSON 序列化失败", e);
        }
    }

    private static String base64Url(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private byte[] hmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("state HMAC 计算失败", e);
        }
    }

    private static byte[] hmacKey(String masterKey) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest((masterKey + "|oauth-state").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
