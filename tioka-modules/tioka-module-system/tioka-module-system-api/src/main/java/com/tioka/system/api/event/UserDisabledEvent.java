package com.tioka.system.api.event;

import java.time.Instant;

/**
 * 用户禁用（FND-04e）。M2 经 outbox 桥接为 {@code tioka.identity.user_disabled.v1}。
 */
public record UserDisabledEvent(String userId, String operatorId, Instant at) {}
