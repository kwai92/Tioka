package com.tioka.system.application.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.system.config.AuthProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class TokenService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final AuthProperties props;
    private final ObjectMapper objectMapper;

    public TokenService(StringRedisTemplate redis, AuthProperties props, ObjectMapper objectMapper) {
        this.redis = redis;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    public TokenPair issue(long userId) {
        return issue(userId, UUID.randomUUID().toString());
    }

    public Optional<TokenClaims> verifyAccess(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return Optional.empty();
        }
        String json = redis.opsForValue().get(atKey(hash(accessToken)));
        if (json == null) {
            return Optional.empty();
        }
        AccessPayload p = fromJson(json, AccessPayload.class);
        return Optional.of(new TokenClaims(p.uid()));
    }

    public TokenPair refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "刷新令牌无效");
        }
        String rtHash = hash(refreshToken);
        String rtRedisKey = rtKey(rtHash);
        String json = redis.opsForValue().get(rtRedisKey);
        if (json == null) {
            String famHint = redis.opsForValue().get(rtFamHintKey(rtHash));
            if (famHint != null) {
                String[] parts = famHint.split(":", 2);
                if (parts.length == 2) {
                    try {
                        revokeFamily(Long.parseLong(parts[0]), parts[1]);
                    } catch (NumberFormatException ignored) {
                        // ignore malformed hint
                    }
                }
            }
            throw new BizException(ErrorCode.UNAUTHORIZED, "刷新令牌无效或已重放");
        }
        RefreshPayload old = fromJson(json, RefreshPayload.class);
        redis.delete(rtRedisKey);
        removeFromUserIndex(old.uid(), rtHash);
        redis.opsForValue().set(rtFamHintKey(rtHash), old.uid() + ":" + old.fam(), props.getRefreshTtl());
        revokeAccessTokensInFamily(old.uid(), old.fam());
        return issue(old.uid(), old.fam());
    }

    public void revoke(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return;
        }
        String atHash = hash(accessToken);
        String json = redis.opsForValue().get(atKey(atHash));
        if (json == null) {
            return;
        }
        AccessPayload p = fromJson(json, AccessPayload.class);
        redis.delete(atKey(atHash));
        removeFromUserIndex(p.uid(), atHash);
        if (p.rt() != null) {
            redis.delete(rtKey(p.rt()));
            removeFromUserIndex(p.uid(), p.rt());
            redis.delete(rtFamHintKey(p.rt()));
        }
    }

    public void revokeAll(long userId) {
        String uk = userKey(userId);
        Set<String> members = redis.opsForSet().members(uk);
        if (members != null) {
            for (String h : members) {
                redis.delete(atKey(h));
                redis.delete(rtKey(h));
                redis.delete(rtFamHintKey(h));
            }
        }
        redis.delete(uk);
    }

    private TokenPair issue(long userId, String familyId) {
        String accessToken = "tka_" + randomToken();
        String refreshToken = "tkr_" + randomToken();
        String atHash = hash(accessToken);
        String rtHash = hash(refreshToken);
        Instant accessExpiresAt = Instant.now().plus(props.getAccessTtl());

        AccessPayload atPayload = new AccessPayload(userId, rtHash, Instant.now().getEpochSecond(), familyId);
        RefreshPayload rtPayload = new RefreshPayload(userId, familyId);

        redis.opsForValue().set(atKey(atHash), toJson(atPayload), props.getAccessTtl());
        redis.opsForValue().set(rtKey(rtHash), toJson(rtPayload), props.getRefreshTtl());
        redis.opsForSet().add(userKey(userId), atHash, rtHash);
        redis.expire(userKey(userId), props.getRefreshTtl());
        redis.opsForValue().set(rtFamHintKey(rtHash), userId + ":" + familyId, props.getRefreshTtl());

        return new TokenPair(accessToken, refreshToken, accessExpiresAt);
    }

    private void revokeFamily(long userId, String fam) {
        revokeAccessTokensInFamily(userId, fam);
        Set<String> members = redis.opsForSet().members(userKey(userId));
        if (members == null) {
            return;
        }
        for (String h : Set.copyOf(members)) {
            String rtJson = redis.opsForValue().get(rtKey(h));
            if (rtJson != null) {
                RefreshPayload p = fromJson(rtJson, RefreshPayload.class);
                if (fam.equals(p.fam())) {
                    redis.delete(rtKey(h));
                    redis.delete(rtFamHintKey(h));
                    removeFromUserIndex(userId, h);
                }
            }
        }
    }

    private void revokeAccessTokensInFamily(long userId, String fam) {
        Set<String> members = redis.opsForSet().members(userKey(userId));
        if (members == null) {
            return;
        }
        for (String h : Set.copyOf(members)) {
            String atJson = redis.opsForValue().get(atKey(h));
            if (atJson != null) {
                AccessPayload p = fromJson(atJson, AccessPayload.class);
                if (fam.equals(p.fam())) {
                    redis.delete(atKey(h));
                    removeFromUserIndex(userId, h);
                }
            }
        }
    }

    private void removeFromUserIndex(long userId, String hash) {
        redis.opsForSet().remove(userKey(userId), hash);
    }

    private static String atKey(String hash) { return "auth:at:" + hash; }
    private static String rtKey(String hash) { return "auth:rt:" + hash; }
    private static String userKey(long uid) { return "auth:user:" + uid; }
    private static String rtFamHintKey(String rtHash) { return "auth:rtfam:" + rtHash; }

    private static String randomToken() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    static String hash(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormatLower.of(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public record TokenPair(String accessToken, String refreshToken, Instant accessExpiresAt) {}
    public record TokenClaims(long userId) {}
    record AccessPayload(long uid, String rt, long iat, String fam) {}
    record RefreshPayload(long uid, String fam) {}

    private static final class HexFormatLower {
        private static final char[] HEX = "0123456789abcdef".toCharArray();
        static String of(byte[] bytes) {
            char[] out = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                int v = bytes[i] & 0xff;
                out[i * 2] = HEX[v >>> 4];
                out[i * 2 + 1] = HEX[v & 0x0f];
            }
            return new String(out);
        }
    }
}
