package com.tioka.runtime.infrastructure;

import com.tioka.runtime.api.grant.DelegationGrant;

/**
 * rt_grant.body_json 的持久化形态（MAS-01）。
 *
 * <p>{@code version} 用于预算回拨的乐观锁（body_json 整体 CAS）；
 * {@code usedTokens} 为该授予自身累计消耗（子 thread Turn token 合计）。
 */
public record GrantBody(DelegationGrant grant, long usedTokens, long version) {}
