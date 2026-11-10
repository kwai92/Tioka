package com.tioka.flow.application.publish;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DAT-04（ADR-029 第 47 条 / M3-02）：发布时遍历节点计算 requiredCapabilities
 * （工具、知识库、MCP、Agent），写入发布记录。
 *
 * <p>M3 口径（登记简化）：Retrieve → kb（config.kbId）；MCP → mcp（config.serverId）；
 * Agent → agent（config.agentId）；LLM/HTTP/IO/Branch 等节点不产生能力需求；
 * 数据集节点类型未出现（DAT-01 无 Flow 数据集节点），预留 tools 段。
 * JSON 编解码用调用方注入的 ObjectMapper（ENG-05，禁止 new ObjectMapper）。
 */
public final class CapabilityCollector {

    public record RequiredCapabilities(
            List<String> tools,
            List<String> kb,
            List<String> mcp,
            List<String> agents) {

        public RequiredCapabilities {
            tools = tools == null ? List.of() : List.copyOf(tools);
            kb = kb == null ? List.of() : List.copyOf(kb);
            mcp = mcp == null ? List.of() : List.copyOf(mcp);
            agents = agents == null ? List.of() : List.copyOf(agents);
        }

        public boolean isEmpty() {
            return tools.isEmpty() && kb.isEmpty() && mcp.isEmpty() && agents.isEmpty();
        }
    }

    private CapabilityCollector() {}

    public static RequiredCapabilities collect(Map<String, Object> graph) {
        Set<String> kb = new LinkedHashSet<>();
        Set<String> mcp = new LinkedHashSet<>();
        Set<String> agents = new LinkedHashSet<>();
        Object nodesRaw = graph == null ? null : graph.get("nodes");
        if (nodesRaw instanceof List<?> nodes) {
            for (Object n : nodes) {
                if (!(n instanceof Map<?, ?> node)) {
                    continue;
                }
                String type = String.valueOf(node.get("type"));
                Object configRaw = node.get("config");
                if (!(configRaw instanceof Map<?, ?> config)) {
                    continue;
                }
                switch (type) {
                    case "Retrieve" -> addIfPresent(kb, config.get("kbId"));
                    case "MCP" -> addIfPresent(mcp, config.get("serverId"));
                    case "Agent" -> addIfPresent(agents, config.get("agentId"));
                    default -> { /* 其他节点无能力需求 */ }
                }
            }
        }
        return new RequiredCapabilities(List.of(), new ArrayList<>(kb),
                new ArrayList<>(mcp), new ArrayList<>(agents));
    }

    public static String toJson(RequiredCapabilities caps, ObjectMapper mapper) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tools", caps.tools());
            m.put("kb", caps.kb());
            m.put("mcp", caps.mcp());
            m.put("agents", caps.agents());
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{\"tools\":[],\"kb\":[],\"mcp\":[],\"agents\":[]}";
        }
    }

    public static RequiredCapabilities fromJson(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return new RequiredCapabilities(List.of(), List.of(), List.of(), List.of());
        }
        try {
            Map<String, Object> m = mapper.readValue(json, new TypeReference<>() {});
            return new RequiredCapabilities(
                    strings(m.get("tools")), strings(m.get("kb")),
                    strings(m.get("mcp")), strings(m.get("agents")));
        } catch (Exception e) {
            return new RequiredCapabilities(List.of(), List.of(), List.of(), List.of());
        }
    }

    private static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(o -> o != null && !String.valueOf(o).isBlank())
                .map(String::valueOf).toList();
    }

    private static void addIfPresent(Set<String> out, Object v) {
        if (v != null && !String.valueOf(v).isBlank()) {
            out.add(String.valueOf(v));
        }
    }
}
