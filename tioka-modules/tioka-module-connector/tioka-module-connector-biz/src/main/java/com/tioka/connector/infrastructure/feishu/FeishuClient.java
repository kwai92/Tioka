package com.tioka.connector.infrastructure.feishu;

import com.tioka.connector.infrastructure.persistence.ImBindingDO;

/**
 * 飞书开放平台客户端（CON-02）：令牌缓存与后续发送/更新/用户信息入口。
 */
public interface FeishuClient {

    /**
     * 获取（或刷新缓存的）tenant_access_token。
     *
     * @param binding IM 绑定（含 appId / secretRef）
     * @return 非空令牌
     */
    String tenantAccessToken(ImBindingDO binding);
}
