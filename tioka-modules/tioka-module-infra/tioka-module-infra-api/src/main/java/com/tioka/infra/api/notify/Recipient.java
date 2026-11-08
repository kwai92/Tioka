package com.tioka.infra.api.notify;

/**
 * 通知收件人（用户 ID 或外部员工号）。
 */
public record Recipient(String userId, String externalStaffId) {
    public static Recipient ofUser(String userId) {
        return new Recipient(userId, null);
    }
}
