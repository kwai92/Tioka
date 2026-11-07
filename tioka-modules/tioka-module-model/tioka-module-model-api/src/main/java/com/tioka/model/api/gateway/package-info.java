/**
 * 模型调用唯一入口（ADR-017、ADR-024）。
 *
 * <p>除 model-biz 外，任何模块不得依赖模型 HTTP 客户端，Flow、Knowledge、Runtime 一律调 {@link
 * com.tioka.model.api.gateway.ModelGateway}。{@code LlmClientApi} 已删除（MH-04）。
 */
package com.tioka.model.api.gateway;
