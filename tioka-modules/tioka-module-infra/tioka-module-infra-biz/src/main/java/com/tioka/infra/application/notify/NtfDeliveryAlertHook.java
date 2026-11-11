package com.tioka.infra.application.notify;

/**
 * 投递终失败告警钩子（NTF-01 接口点）：3 次重试耗尽 → FAILED 后回调。
 * 正式告警出口（飞书 / 邮件）依赖 NTF-02 渠道实现，本批仅留接口不注册实现（登记待办）。
 */
public interface NtfDeliveryAlertHook {

    void onDeliveryFailed(String deliveryId, String channel, String recipient, String lastError);
}
