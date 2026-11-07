/**
 * Runtime 内核对外契约：thread / turn / item 三级模型（ADR-016、ADR-027、ADR-029）。
 *
 * <p>Chat、Flow、Orchestration、IM 四个入口只能通过 {@link com.tioka.runtime.api.kernel.RuntimeApi}
 * 执行 Agent。
 */
package com.tioka.runtime.api.kernel;
