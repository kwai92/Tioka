package com.tioka.connector.application.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.connector.infrastructure.ConnectorSecretBridge;
import com.tioka.connector.infrastructure.ImBindingRepository;
import com.tioka.connector.infrastructure.persistence.ImBindingDO;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.system.api.spi.EgressClient;
import com.tioka.system.api.spi.SecretStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * IM 绑定管理（IM-ADM）：增删改查、密钥只写不读、回调地址与连通性检查。
 */
@Service
public class ImBindingService {

    private static final String CHANNEL_FEISHU = "feishu";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String NS_APP_SECRET = "im-app-secret";
    private static final String NS_ENCRYPT_KEY = "im-encrypt-key";
    private static final String NS_VERIFICATION = "im-verification-token";
    private static final String NS_CARD_SIGN = "im-card-sign";

    private final ImBindingRepository repository;
    private final SecretStore secretStore;
    private final ConnectorSecretBridge secretBridge;
    private final EgressClient egress;
    private final PrincipalHolder principals;
    private final ObjectMapper mapper;
    private final String publicUrl;
    private final String feishuApiBase;
    private final SecureRandom secureRandom = new SecureRandom();

    public ImBindingService(ImBindingRepository repository,
                            SecretStore secretStore,
                            ConnectorSecretBridge secretBridge,
                            EgressClient egress,
                            PrincipalHolder principals,
                            ObjectMapper mapper,
                            @Value("${tioka.public-url:http://localhost:8080}") String publicUrl,
                            @Value("${tioka.im.feishu.api-base:https://open.feishu.cn}") String feishuApiBase) {
        this.repository = repository;
        this.secretStore = secretStore;
        this.secretBridge = secretBridge;
        this.egress = egress;
        this.principals = principals;
        this.mapper = mapper;
        this.publicUrl = trimTrailingSlash(publicUrl == null || publicUrl.isBlank()
                ? "http://localhost:8080" : publicUrl.trim());
        this.feishuApiBase = trimTrailingSlash(feishuApiBase == null || feishuApiBase.isBlank()
                ? "https://open.feishu.cn" : feishuApiBase.trim());
    }

    @Transactional
    public ImBindingView create(CreateImBindingRequest req) {
        Principal principal = requirePrincipal();
        if (req == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请求体不能为空");
        }
        String workspaceId = firstNonBlank(req.workspaceId(), principal.workspaceId());
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "workspaceId 不能为空");
        }
        if (!Objects.equals(workspaceId, principal.workspaceId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "只能在当前空间创建 IM 绑定");
        }
        String channel = normalizeChannel(req.channel());
        String appId = requireText(req.appId(), "appId");
        String appSecret = requireText(req.appSecret(), "appSecret");
        String identityMatch = normalizeIdentityMatch(req.identityMatch());

        if (repository.findByChannelAndAppId(channel, appId).isPresent()) {
            throw new BizException(ErrorCode.CONFLICT, "同渠道 appId 已存在");
        }

        // imb_ + ULID(26) = 30 ≤ VARCHAR(32)
        String id = Ids.newId("imb");

        String secretRef = secretStore.put(NS_APP_SECRET, id, appSecret);
        String encryptKeyRef = blankToNull(req.encryptKey()) == null
                ? null : secretStore.put(NS_ENCRYPT_KEY, id, req.encryptKey().trim());
        String verificationTokenRef = blankToNull(req.verificationToken()) == null
                ? null : secretStore.put(NS_VERIFICATION, id, req.verificationToken().trim());
        String cardSignKeyRef = secretStore.put(NS_CARD_SIGN, id, randomCardSignKey());

        ImBindingDO row = new ImBindingDO();
        row.setId(id);
        row.setWorkspaceId(workspaceId);
        row.setChannel(channel);
        row.setAppId(appId);
        row.setSecretRef(secretRef);
        row.setEncryptKeyRef(encryptKeyRef);
        row.setVerificationTokenRef(verificationTokenRef);
        row.setCardSignKeyRef(cardSignKeyRef);
        row.setDefaultAgentId(blankToNull(req.defaultAgentId()));
        row.setOrchEnabled(req.orchEnabled() != null && req.orchEnabled());
        row.setIdentityMatch(identityMatch);
        row.setStatus(STATUS_ACTIVE);
        repository.insert(row);

        return toView(row, blankToNull(req.name()));
    }

    public List<ImBindingView> list() {
        Principal principal = requirePrincipal();
        return repository.listByWorkspace(principal.workspaceId()).stream()
                .map(row -> toView(row, null))
                .toList();
    }

    public ImBindingView get(String id) {
        return toView(requireOwned(id), null);
    }

    @Transactional
    public ImBindingView update(String id, UpdateImBindingRequest req) {
        ImBindingDO row = requireOwned(id);
        if (req == null) {
            return toView(row, null);
        }
        if (req.defaultAgentId() != null) {
            row.setDefaultAgentId(blankToNull(req.defaultAgentId()));
        }
        if (req.orchEnabled() != null) {
            row.setOrchEnabled(req.orchEnabled());
        }
        if (req.identityMatch() != null) {
            row.setIdentityMatch(normalizeIdentityMatch(req.identityMatch()));
        }
        if (req.enabled() != null) {
            row.setStatus(req.enabled() ? STATUS_ACTIVE : STATUS_DISABLED);
        }
        if (req.status() != null && !req.status().isBlank()) {
            row.setStatus(normalizeStatus(req.status()));
        }
        if (blankToNull(req.appSecret()) != null) {
            row.setSecretRef(secretStore.put(NS_APP_SECRET, id, req.appSecret().trim()));
        }
        if (blankToNull(req.encryptKey()) != null) {
            row.setEncryptKeyRef(secretStore.put(NS_ENCRYPT_KEY, id, req.encryptKey().trim()));
        }
        if (blankToNull(req.verificationToken()) != null) {
            row.setVerificationTokenRef(secretStore.put(NS_VERIFICATION, id, req.verificationToken().trim()));
        }
        repository.updateById(row);
        return toView(requireOwned(id), blankToNull(req.name()));
    }

    @Transactional
    public void delete(String id) {
        ImBindingDO row = requireOwned(id);
        deleteSecret(row.getSecretRef());
        deleteSecret(row.getEncryptKeyRef());
        deleteSecret(row.getVerificationTokenRef());
        deleteSecret(row.getCardSignKeyRef());
        repository.softDeleteById(id);
    }

    public ConnectivityCheckResult checkConnectivity(String id) {
        ImBindingDO row = requireOwned(id);
        if (!CHANNEL_FEISHU.equals(row.getChannel())) {
            return new ConnectivityCheckResult(false, "仅支持飞书渠道连通性检查");
        }
        String appSecret = secretBridge.resolveOrNull(row.getSecretRef());
        if (appSecret == null || appSecret.isBlank()) {
            return new ConnectivityCheckResult(false, "应用密钥缺失");
        }
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "app_id", row.getAppId(),
                    "app_secret", appSecret));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(feishuApiBase + "/open-apis/auth/v3/tenant_access_token/internal"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = egress.send(request);
            JsonNode root = mapper.readTree(resp.body() == null ? "{}" : resp.body());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return new ConnectivityCheckResult(false, "HTTP " + resp.statusCode());
            }
            if (root.path("code").asInt(-1) != 0) {
                return new ConnectivityCheckResult(false,
                        "飞书返回 code=" + root.path("code").asInt() + " msg=" + root.path("msg").asText(""));
            }
            String token = root.path("tenant_access_token").asText("");
            if (token.isBlank()) {
                return new ConnectivityCheckResult(false, "未返回 tenant_access_token");
            }
            return new ConnectivityCheckResult(true, "ok");
        } catch (BizException e) {
            return new ConnectivityCheckResult(false, e.getMessage());
        } catch (Exception e) {
            return new ConnectivityCheckResult(false, "连通性检查失败: " + e.getMessage());
        }
    }

    private ImBindingDO requireOwned(String id) {
        Principal principal = requirePrincipal();
        if (id == null || id.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "id 不能为空");
        }
        ImBindingDO row = repository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "IM 绑定不存在"));
        if (!Objects.equals(row.getWorkspaceId(), principal.workspaceId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "IM 绑定不存在");
        }
        return row;
    }

    private Principal requirePrincipal() {
        return principals.find()
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED, "未登录"));
    }

    private ImBindingView toView(ImBindingDO row, String displayName) {
        String name = displayName != null ? displayName
                : (row.getAppId() == null ? row.getId() : row.getAppId());
        boolean enabled = STATUS_ACTIVE.equalsIgnoreCase(row.getStatus());
        return new ImBindingView(
                row.getId(),
                row.getChannel(),
                name,
                row.getWorkspaceId(),
                row.getAppId(),
                row.getDefaultAgentId(),
                Boolean.TRUE.equals(row.getOrchEnabled()),
                enabled,
                row.getIdentityMatch(),
                callbackUrl(row.getChannel(), row.getId()),
                formatTime(row.getCreateAt()),
                formatTime(row.getUpdateAt())
        );
    }

    private String callbackUrl(String channel, String bindingId) {
        return publicUrl + "/api/connector/im/" + channel + "/" + bindingId + "/events";
    }

    private void deleteSecret(String ref) {
        if (ref == null || ref.isBlank() || !ref.contains("::")) {
            return;
        }
        String[] parts = ref.split("::", 2);
        secretStore.delete(parts[0], parts[1]);
    }

    private String randomCardSignKey() {
        byte[] buf = new byte[32];
        secureRandom.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    private static String normalizeChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return CHANNEL_FEISHU;
        }
        String c = channel.trim().toLowerCase(Locale.ROOT);
        if (!CHANNEL_FEISHU.equals(c)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "M2 仅支持 channel=feishu");
        }
        return c;
    }

    private static String normalizeIdentityMatch(String raw) {
        if (raw == null || raw.isBlank()) {
            return "link";
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (!"link".equals(v) && !"email".equals(v)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "identityMatch 须为 link|email");
        }
        return v;
    }

    private static String normalizeStatus(String raw) {
        String v = raw.trim().toUpperCase(Locale.ROOT);
        if (!STATUS_ACTIVE.equals(v) && !STATUS_DISABLED.equals(v)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "status 须为 ACTIVE|DISABLED");
        }
        return v;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, field + " 不能为空");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }

    private static String trimTrailingSlash(String url) {
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static String formatTime(LocalDateTime time) {
        if (time == null) {
            return null;
        }
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(time.atOffset(ZoneOffset.UTC));
    }

    public record CreateImBindingRequest(
            String name,
            String workspaceId,
            String channel,
            String appId,
            String appSecret,
            String encryptKey,
            String verificationToken,
            String defaultAgentId,
            Boolean orchEnabled,
            String identityMatch
    ) {
    }

    public record UpdateImBindingRequest(
            String name,
            String defaultAgentId,
            Boolean orchEnabled,
            Boolean enabled,
            String status,
            String identityMatch,
            String appSecret,
            String encryptKey,
            String verificationToken
    ) {
    }

    public record ImBindingView(
            String id,
            String channel,
            String name,
            String workspaceId,
            String appId,
            String defaultAgentId,
            boolean orchEnabled,
            boolean enabled,
            String identityMatch,
            String callbackUrl,
            String createAt,
            String updateAt
    ) {
    }

    public record ConnectivityCheckResult(boolean ok, String message) {
    }
}
