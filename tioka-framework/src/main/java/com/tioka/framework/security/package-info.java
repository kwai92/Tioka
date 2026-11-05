/**
 * 身份与权限契约（ADR-015）。
 *
 * <p>每个请求与每个后台任务都携带一个不可变的 {@link com.tioka.framework.security.Principal}。
 * 本包只放契约，实现位于 system-biz；实现 PR 不得修改本包（ADR-030）。
 */
package com.tioka.framework.security;
