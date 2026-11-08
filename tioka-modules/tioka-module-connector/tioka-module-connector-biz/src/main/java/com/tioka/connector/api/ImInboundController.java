package com.tioka.connector.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tioka.connector.application.im.InboundService;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.PublicApi;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 飞书等 IM 平台事件订阅回调（PK-19a / CON-02）。平台签名校验；无登录态。
 */
@RestController
@RequestMapping("/api/connector/im")
public class ImInboundController {

    private final InboundService inbound;

    public ImInboundController(InboundService inbound) {
        this.inbound = inbound;
    }

    @PublicApi(reason = "IM 平台事件订阅回调，以平台签名校验")
    @PostMapping(value = "/{channel}/{bindingId}/events",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> events(
            @PathVariable("channel") String channel,
            @PathVariable("bindingId") String bindingId,
            @RequestHeader Map<String, String> headers,
            @RequestBody(required = false) String rawBody) {
        if (channel == null || channel.isBlank() || bindingId == null || bindingId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "channel/bindingId 不能为空");
        }
        Map<String, String> normalized = normalizeHeaders(headers);
        ObjectNode body = inbound.handle(channel.trim().toLowerCase(Locale.ROOT), bindingId.trim(),
                normalized, rawBody == null ? "" : rawBody);
        return ResponseEntity.ok(body);
    }

    private static Map<String, String> normalizeHeaders(Map<String, String> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        if (headers == null) {
            return out;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }
}
