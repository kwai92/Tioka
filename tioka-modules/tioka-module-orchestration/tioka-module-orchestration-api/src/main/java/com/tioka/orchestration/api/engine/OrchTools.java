package com.tioka.orchestration.api.engine;

/**
 * 编排内置工具名（M2-02 ORC-03）。
 */
public final class OrchTools {

    /** Agent 交回结局的内置工具；仅 {@code origin=orch} 的 Turn 可见。 */
    public static final String RECEIPT = "orch.receipt";

    private OrchTools() {
    }
}
