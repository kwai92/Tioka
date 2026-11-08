package com.tioka.model.api;

/** 路由方案查询（含 AST-02 发布校验加锁）。 */
public interface RoutingPlanQueryApi {
    boolean isPublished(String routingPlanId);

    /**
     * 发布校验：对路由方案行加 {@code FOR SHARE}。
     * 已发布即视为 CHAT 可用（方案本身无 purpose 列）。
     */
    ChatAvailability lockForChat(String routingPlanId);

    enum ChatAvailability {
        MISSING,
        UNPUBLISHED,
        AVAILABLE
    }
}

