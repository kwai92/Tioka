package com.tioka.system.api.event;

/**
 * 空间成员移除（FND-04e）。M2 经 outbox 桥接为 {@code tioka.identity.member_removed.v1}。
 *
 * @param source {@code manual} 或 {@code oidc:{idp}}（与 {@code sys_member.source} 一致）
 */
public record MemberRemovedEvent(String workspaceId, String userId, String source) {
    public static final String SOURCE_MANUAL = "manual";

    public static String oidcSource(String idp) {
        return "oidc:" + idp;
    }
}
