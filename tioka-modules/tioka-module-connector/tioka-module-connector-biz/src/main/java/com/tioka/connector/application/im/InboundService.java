package com.tioka.connector.application.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tioka.connector.api.mq.ConnectorMqTopics;
import com.tioka.connector.infrastructure.ConnectorSecretBridge;
import com.tioka.connector.infrastructure.ImBindingRepository;
import com.tioka.connector.infrastructure.ImInboundDedupeRepository;
import com.tioka.connector.infrastructure.feishu.FeishuCrypto;
import com.tioka.connector.infrastructure.persistence.ImBindingDO;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * IM 入站同步路径：验签解密 → 去重 → 写 outbox → 立刻应答（M2-03 / PK-19a）。
 */
@Service
public class InboundService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final Set<String> HANDLED_TYPES = Set.of(
            "im.message.receive_v1",
            "card.action.trigger",
            "im.chat.member.bot.added_v1");

    private final ImBindingRepository bindings;
    private final ImInboundDedupeRepository dedupe;
    private final ConnectorSecretBridge secrets;
    private final EventPublisher events;
    private final PrincipalHolder principals;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    public InboundService(ImBindingRepository bindings,
                          ImInboundDedupeRepository dedupe,
                          ConnectorSecretBridge secrets,
                          EventPublisher events,
                          PrincipalHolder principals,
                          TransactionTemplate tx,
                          ObjectMapper mapper) {
        this.bindings = bindings;
        this.dedupe = dedupe;
        this.secrets = secrets;
        this.events = events;
        this.principals = principals;
        this.tx = tx;
        this.mapper = mapper;
    }

    /**
     * 处理回调；返回体直接作为 HTTP 响应（url_verification 含 challenge，其余通常空对象）。
     */
    public ObjectNode handle(String channel, String bindingId, Map<String, String> headers, String rawBody) {
        ImBindingDO binding = bindings.findByIdUnscoped(bindingId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "IM 绑定不存在"));
        if (!channelEquals(channel, binding.getChannel())
                || !STATUS_ACTIVE.equalsIgnoreCase(nullToEmpty(binding.getStatus()))) {
            throw new BizException(ErrorCode.NOT_FOUND, "IM 绑定不存在或已停用");
        }

        String plain = openBody(binding, headers, rawBody);
        JsonNode root;
        try {
            root = mapper.readTree(plain == null || plain.isBlank() ? "{}" : plain);
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "事件 JSON 非法");
        }

        // url_verification（加密或明文）
        String type = text(root, "type");
        if ("url_verification".equals(type)) {
            return handleUrlVerification(binding, root);
        }

        String eventType;
        String eventId;
        String token;
        JsonNode eventNode;
        if ("2.0".equals(text(root, "schema"))) {
            JsonNode header = root.path("header");
            eventType = text(header, "event_type");
            eventId = text(header, "event_id");
            token = text(header, "token");
            eventNode = root.path("event");
        } else {
            eventType = type;
            eventId = firstNonBlank(text(root, "event_id"), text(root, "uuid"));
            token = text(root, "token");
            eventNode = root.path("event").isMissingNode() ? root : root.path("event");
        }

        verifyToken(binding, token);

        if (eventId == null || eventId.isBlank()) {
            // 无 event_id 则无法去重；忽略
            return mapper.createObjectNode();
        }

        boolean first = Boolean.TRUE.equals(tx.execute(status -> {
            boolean inserted = dedupe.tryInsert(binding.getId(), eventId);
            if (!inserted) {
                return false;
            }
            if (!HANDLED_TYPES.contains(eventType)) {
                return true;
            }
            publishInbound(binding, eventId, eventType, eventNode);
            return true;
        }));

        if (!first) {
            return mapper.createObjectNode();
        }
        return mapper.createObjectNode();
    }

    private ObjectNode handleUrlVerification(ImBindingDO binding, JsonNode root) {
        verifyToken(binding, text(root, "token"));
        String challenge = text(root, "challenge");
        ObjectNode out = mapper.createObjectNode();
        out.put("challenge", challenge == null ? "" : challenge);
        return out;
    }

    private void publishInbound(ImBindingDO binding, String eventId, String eventType, JsonNode eventNode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bindingId", binding.getId());
        payload.put("eventId", eventId);
        payload.put("eventType", eventType);
        payload.put("event", mapper.convertValue(eventNode == null || eventNode.isMissingNode()
                ? mapper.createObjectNode() : eventNode, Map.class));
        payload.put("channel", binding.getChannel());
        payload.put("workspaceId", binding.getWorkspaceId());

        Principal system = new Principal(
                "0",
                binding.getWorkspaceId(),
                PrincipalType.SYSTEM,
                Set.of(),
                Set.of("*"),
                "system");
        principals.runAs(system, () -> events.publish(new DomainEvent(
                ConnectorMqTopics.IM_INBOUND,
                ConnectorMqTopics.TOPIC,
                binding.getId() + ":" + eventType,
                payload,
                0,
                null,
                "im_binding:" + binding.getId())));
    }

    private String openBody(ImBindingDO binding, Map<String, String> headers, String rawBody) {
        String encryptKey = secrets.resolveOrNull(binding.getEncryptKeyRef());
        if (encryptKey == null || encryptKey.isBlank()) {
            return rawBody;
        }
        String ts = header(headers, "X-Lark-Request-Timestamp");
        String nonce = header(headers, "X-Lark-Request-Nonce");
        String sig = header(headers, "X-Lark-Signature");
        String encryptField = null;
        try {
            JsonNode wrapped = mapper.readTree(rawBody == null || rawBody.isBlank() ? "{}" : rawBody);
            if (wrapped.hasNonNull("encrypt")) {
                encryptField = wrapped.get("encrypt").asText();
            }
        } catch (Exception ignored) {
            // 非 JSON 包装时走明文验签路径
        }
        try {
            return FeishuCrypto.verifyAndDecrypt(encryptKey, ts, nonce, sig, rawBody, encryptField);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, e.getMessage());
        }
    }

    private void verifyToken(ImBindingDO binding, String token) {
        String expected = secrets.resolveOrNull(binding.getVerificationTokenRef());
        if (expected == null || expected.isBlank()) {
            return;
        }
        if (token == null || !expected.equals(token)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "verification token 不匹配");
        }
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        String direct = headers.get(name);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText(null);
        return s == null || s.isBlank() ? null : s;
    }

    private static boolean channelEquals(String a, String b) {
        return nullToEmpty(a).equalsIgnoreCase(nullToEmpty(b));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }
}
