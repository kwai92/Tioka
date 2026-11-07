package com.tioka.connector.api.im;

import java.util.Map;

/**
 * 平台回调原始请求（验签前）。
 *
 * @param bindingId 绑定 ID
 * @param headers   HTTP 头（签名、时间戳等）
 * @param body      原始 body 字节的 UTF-8 文本或已解析 JSON 字符串
 * @param attrs     实现可附带的解析中间态
 */
public record InboundRequest(
        String bindingId,
        Map<String, String> headers,
        String body,
        Map<String, String> attrs) {

    public InboundRequest {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        attrs = attrs == null ? Map.of() : Map.copyOf(attrs);
    }
}
