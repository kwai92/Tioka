package com.tioka.infra.application.notify;

import com.tioka.infra.api.notify.Recipient;
import com.tioka.infra.infrastructure.SysExternalIdentityRepository;

import java.util.Locale;
import java.util.Optional;

/**
 * 收件人解析（NTF-01 / M3-03 §15 L592）：按 FND-09 外部身份映射（sys_external_identity）
 * 把 Tioka 用户 ID 或业务系统员工号解析为渠道侧外部 ID。
 *
 * <p>规则：①按 (source=渠道名, user_id) 查；②兜底按 (source=渠道名, external_id) 查
 * （recipient 本身即员工号 / open_id）；都查不到 → 空（调用侧标
 * {@code RECIPIENT_UNMAPPED}，status=FAILED）。M3-03 原文的降级「只发邮件」待
 * NTF-02 邮件渠道实现后才可兑现（登记偏差）。
 */
public class RecipientResolver {

    private final SysExternalIdentityRepository identities;

    public RecipientResolver(SysExternalIdentityRepository identities) {
        this.identities = identities;
    }

    /** 渠道对应的身份来源：与 sys_external_identity.source 对齐（feishu 行即飞书 open_id）。 */
    public static String sourceOf(String channel) {
        return channel == null ? "" : channel.toLowerCase(Locale.ROOT);
    }

    /** 解析外部 ID；找不到返回空。 */
    public Optional<String> resolve(String recipient, String channel) {
        if (recipient == null || recipient.isBlank()) {
            return Optional.empty();
        }
        String source = sourceOf(channel);
        String externalId = identities.findExternalIdByUser(recipient, source);
        if (externalId != null && !externalId.isBlank()) {
            return Optional.of(externalId);
        }
        externalId = identities.findExternalId(recipient, source);
        if (externalId != null && !externalId.isBlank()) {
            return Optional.of(externalId);
        }
        return Optional.empty();
    }

    /** SPI 收件人：解析后以外部 ID 为员工号交给渠道实现（NTF-02 渠道可再细分）。 */
    public Optional<Recipient> resolveRecipient(String recipient, String channel) {
        return resolve(recipient, channel).map(externalId -> new Recipient(null, externalId));
    }
}
