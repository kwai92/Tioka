/**
 * 编排对外契约（ADR-020 及其对齐修订，施工设计 M2-02）。
 *
 * <p>ORC-03 完成时删除旧的 {@code com.tioka.orchestration.api.OrchApi}（claim、heartbeat、openAttention 等），
 * 领取与心跳不再对外暴露，人审答复统一走 {@code RuntimeApi.decideApproval}。
 */
package com.tioka.orchestration.api.engine;
