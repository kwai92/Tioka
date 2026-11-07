package com.tioka.connector.api.im;

import java.util.Map;

/**
 * 卡片按钮回调。
 *
 * @param approvalId 审批 ID，可空（非审批卡片）
 * @param action     按钮动作码
 * @param token      HMAC 签名令牌，可空
 * @param actorExternalUserId 点击人外部 ID
 * @param values     表单值
 */
public record CardAction(
        String approvalId,
        String action,
        String token,
        String actorExternalUserId,
        Map<String, Object> values) {

    public CardAction {
        values = values == null ? Map.of() : Map.copyOf(values);
    }
}
