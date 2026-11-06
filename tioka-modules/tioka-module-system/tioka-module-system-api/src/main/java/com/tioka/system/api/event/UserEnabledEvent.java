package com.tioka.system.api.event;

import java.time.Instant;

/**
 * 用户启用（FND-04e）。监听方仅清 Principal / 组缓存。
 */
public record UserEnabledEvent(String userId, String operatorId, Instant at) {}
