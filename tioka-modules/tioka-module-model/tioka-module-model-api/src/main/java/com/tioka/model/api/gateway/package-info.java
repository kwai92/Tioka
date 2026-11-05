/**
 * 模型调用唯一入口（ADR-017、ADR-024）。
 *
 * <p>除 model-biz 外，任何模块不得依赖模型 HTTP 客户端，Flow、Knowledge、Runtime 一律调 {@link
 * com.tioka.model.api.gateway.ModelGateway}。{@code LlmClientApi} 在调用方迁移后降为 model-biz 内部类。
 */
package com.tioka.model.api.gateway;
