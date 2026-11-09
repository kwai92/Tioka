package com.tioka.runtime.application.grant;

import java.util.List;

/**
 * 授予缓存抽象（ADR-039：Redis 60 秒 + 失效广播）。测试用内存 / mock 实现，禁止直连 Redis。
 */
public interface GrantCacheStore {

    String get(String grantId);

    void put(String grantId, String bodyJson);

    /** 事务提交后 DEL 对应键并发布 {@code rt:grant:revoked}（各实例清本地缓存）。 */
    void evict(List<String> grantIds);

    void publishRevoked(List<String> grantIds);
}
