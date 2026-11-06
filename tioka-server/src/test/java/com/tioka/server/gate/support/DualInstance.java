package com.tioka.server.gate.support;

import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;

/**
 * ENG-04 / ENG-04b：{@code GateCluster.start(2)} 的薄封装，兼容既有 G0 用例。
 */
public final class DualInstance implements AutoCloseable {
    private final GateCluster cluster;

    private DualInstance(GateCluster cluster) {
        this.cluster = cluster;
    }

    public static DualInstance start() {
        return start("three-tools", Map.of(), true);
    }

    public static DualInstance start(String llmScript, Map<String, Object> extraProps) {
        return start(llmScript, extraProps, true);
    }

    /** @param dual 为 false 时只启实例 A（并发闸门用，避免双池压垮 PG） */
    public static DualInstance start(String llmScript, Map<String, Object> extraProps, boolean dual) {
        // G0 路径不启 Keycloak，控制 CI 时长
        GateCluster cluster = GateCluster.start(dual ? 2 : 1, llmScript, extraProps, false);
        return new DualInstance(cluster);
    }

    public ConfigurableApplicationContext a() { return cluster.a(); }

    public ConfigurableApplicationContext b() {
        if (cluster.size() < 2) {
            throw new IllegalStateException("单实例模式无 B");
        }
        return cluster.b();
    }

    public int portA() { return cluster.portA(); }

    public int portB() {
        if (cluster.size() < 2) {
            throw new IllegalStateException("单实例模式无 B");
        }
        return cluster.portB();
    }

    public LlmStubServer llm() { return cluster.llm(); }

    public McpStubServer mcp() { return cluster.mcp(); }

    public String baseUrlA() { return cluster.baseUrlA(); }

    public String baseUrlB() { return cluster.baseUrlB(); }

    public GateCluster cluster() { return cluster; }

    @Override
    public void close() {
        cluster.close();
    }
}
