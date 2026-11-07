package com.tioka.flow.dsl;

import com.tioka.flow.api.dsl.FlowDslDocument;
import com.tioka.flow.api.dsl.FlowDslValidator;
import com.tioka.flow.api.dsl.FlowPublishContext;
import com.tioka.flow.api.dsl.PortType;
import com.tioka.flow.api.dsl.ValidationIssue;
import com.tioka.flow.api.dsl.ValidationResult;
import com.tioka.flow.api.dsl.ValidationSeverity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ADR-043 §3.7 发布校验（未接线旧引擎；CEL 依赖未入仓前只做引用语法校验）。
 */
public class DefaultFlowDslValidator implements FlowDslValidator {

    private static final int MAX_NODES = 200;
    private static final int MAX_SUBGRAPH_DEPTH = 3;
    private static final Pattern EXPR = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern NODE_ID = Pattern.compile("^[a-z0-9_]{1,64}$");
    private static final Set<String> DEPRECATED = Set.of("legacy_http");

    @Override
    public ValidationResult validate(FlowDslDocument document, FlowPublishContext context) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (document == null) {
            issues.add(err("STRUCTURE_EMPTY", "文档为空", "$"));
            return new ValidationResult(issues);
        }
        FlowPublishContext ctx = context == null ? FlowPublishContext.permissive() : context;
        checkStructure(document, issues);
        checkTypesAndExpr(document, issues);
        checkRefsAndPerm(document, ctx, issues);
        checkSubflow(ctx, issues);
        checkLimits(document, issues);
        checkPublication(document, ctx, issues);
        checkQuality(document, issues);
        return new ValidationResult(List.copyOf(issues));
    }

    private void checkStructure(FlowDslDocument doc, List<ValidationIssue> issues) {
        List<Map<String, Object>> nodes = nodesOf(doc);
        List<Map<String, Object>> edges = edgesOf(doc);
        long inputs = nodes.stream().filter(n -> "input".equals(str(n.get("type")))).count();
        long outputs = nodes.stream().filter(n -> "output".equals(str(n.get("type")))).count();
        if (inputs != 1) {
            issues.add(err("STRUCTURE_INPUT", "顶层须恰好一个 Input 节点，实际=" + inputs, "nodes"));
        }
        if (outputs < 1) {
            issues.add(err("STRUCTURE_OUTPUT", "顶层至少需要一个 Output 节点", "nodes"));
        }
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> n : nodes) {
            String id = str(n.get("id"));
            if (id == null || !NODE_ID.matcher(id).matches()) {
                issues.add(err("STRUCTURE_NODE_ID", "非法节点 id: " + id, "nodes"));
            } else if (!ids.add(id)) {
                issues.add(err("STRUCTURE_NODE_ID", "节点 id 重复: " + id, "nodes." + id));
            }
        }
        if (hasCycle(ids, edges)) {
            issues.add(err("STRUCTURE_CYCLE", "图中存在环", "edges"));
        }
        Set<String> reachable = reachableFromInput(nodes, edges);
        for (String id : ids) {
            if (!reachable.contains(id)) {
                issues.add(err("STRUCTURE_UNREACHABLE", "从 Input 不可达: " + id, "nodes." + id));
            }
        }
        int depth = maxSubgraphDepthFromRoots(doc);
        if (depth > MAX_SUBGRAPH_DEPTH) {
            issues.add(err("STRUCTURE_SUBGRAPH_DEPTH", "子图嵌套深度 " + depth + " 超过 " + MAX_SUBGRAPH_DEPTH, "subgraphs"));
        }
    }

    private void checkTypesAndExpr(FlowDslDocument doc, List<ValidationIssue> issues) {
        Map<String, Map<String, String>> portTypes = resolvePortTypes(doc);
        for (Map<String, Object> e : edgesOf(doc)) {
            String from = str(e.get("fromNode"));
            String fromPort = str(e.get("fromPort")) == null ? "out" : str(e.get("fromPort"));
            String to = str(e.get("toNode"));
            String toPort = str(e.get("toPort")) == null ? "in" : str(e.get("toPort"));
            String src = portTypes.getOrDefault(from, Map.of()).get(fromPort);
            String dst = portTypes.getOrDefault(to, Map.of()).get(toPort);
            if (src != null && dst != null && !PortTypeCompatibility.compatible(src, dst)) {
                issues.add(err("TYPE_PORT", "端口类型不兼容: " + src + " → " + dst, "edges"));
            }
            if (src != null && PortType.ANY == safeParse(src)
                    || dst != null && PortType.ANY == safeParse(dst)) {
                // 质量警告在 checkQuality 统一发，避免重复；此处仅兼容放行
            }
        }
        Set<String> nodeIds = new HashSet<>();
        for (Map<String, Object> n : nodesOf(doc)) {
            nodeIds.add(str(n.get("id")));
        }
        Set<String> inputNames = new HashSet<>();
        if (doc.inputs() != null) {
            for (Map<String, Object> in : doc.inputs()) {
                inputNames.add(str(in.get("name")));
            }
        }
        Set<String> constNames = doc.constants() == null ? Set.of() : doc.constants().keySet();
        for (Map<String, Object> n : nodesOf(doc)) {
            collectExprs(n).forEach(expr -> validateExpr(expr, nodeIds, inputNames, constNames, str(n.get("id")), issues));
        }
    }

    private void checkRefsAndPerm(FlowDslDocument doc, FlowPublishContext ctx, List<ValidationIssue> issues) {
        for (Map<String, Object> n : nodesOf(doc)) {
            String type = str(n.get("type"));
            Map<String, Object> config = map(n.get("config"));
            String path = "nodes." + str(n.get("id"));
            if ("llm".equals(type)) {
                String model = str(config.get("model"));
                if (model != null && !ctx.modelAliasExists(model, "chat")) {
                    issues.add(err("REF_MODEL", "模型别名不存在或用途不匹配: " + model, path));
                }
                String prompt = str(config.get("prompt"));
                if (prompt != null && !ctx.promptTemplatePublished(prompt)) {
                    issues.add(err("REF_PROMPT", "Prompt 模板未发布或不完整: " + prompt, path));
                }
                checkUse(ctx, "model", model, path, issues);
                checkUse(ctx, "prompt", prompt, path, issues);
            } else if ("agent".equals(type)) {
                String ref = str(config.get("agentRef"));
                if (ref != null && !ctx.resourceExists("agent", ref)) {
                    issues.add(err("REF_RESOURCE", "Agent 引用不存在: " + ref, path));
                }
                checkUse(ctx, "agent", ref, path, issues);
            } else if ("subflow".equals(type)) {
                String ref = str(config.get("flowRef"));
                if (ref != null && !ctx.resourceExists("flow", ref)) {
                    issues.add(err("REF_RESOURCE", "子流程引用不存在: " + ref, path));
                }
                checkUse(ctx, "flow", ref, path, issues);
            } else if ("knowledge".equals(type)) {
                String ref = str(config.get("kb"));
                if (ref != null && !ctx.resourceExists("kb", ref)) {
                    issues.add(err("REF_RESOURCE", "知识库引用不存在: " + ref, path));
                }
                checkUse(ctx, "kb", ref, path, issues);
            } else if ("mcp".equals(type)) {
                String ref = str(config.get("mcpSnapshot"));
                if (ref != null && !ctx.resourceExists("mcp", ref)) {
                    issues.add(err("REF_RESOURCE", "MCP 快照不存在: " + ref, path));
                }
                checkUse(ctx, "mcp", ref, path, issues);
            }
            String schema = str(config.get("schema"));
            if (schema != null) {
                if (!ctx.resourceExists("schema", schema)) {
                    issues.add(err("REF_RESOURCE", "Schema 引用不存在: " + schema, path));
                }
                checkUse(ctx, "schema", schema, path, issues);
            }
            String credential = str(config.get("credential"));
            if (credential != null) {
                if (!ctx.resourceExists("credential", credential)) {
                    issues.add(err("REF_RESOURCE", "凭证引用不存在: " + credential, path));
                }
                checkUse(ctx, "credential", credential, path, issues);
            }
        }
    }

    private static void checkUse(FlowPublishContext ctx, String kind, String ref, String path, List<ValidationIssue> issues) {
        if (ref == null) {
            return;
        }
        if (!ctx.publisherCanUse(kind, ref)) {
            issues.add(err("PERM_USE", "发布者缺少 USE: " + kind + "/" + ref, path));
        }
    }

    private void checkSubflow(FlowPublishContext ctx, List<ValidationIssue> issues) {
        Set<String> edges = ctx.subflowEdges();
        if (edges == null || edges.isEmpty()) {
            return;
        }
        Map<String, List<String>> g = new HashMap<>();
        for (String e : edges) {
            String[] p = e.split("->", 2);
            if (p.length != 2) {
                continue;
            }
            g.computeIfAbsent(p[0].trim(), k -> new ArrayList<>()).add(p[1].trim());
        }
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String n : g.keySet()) {
            if (dfsCycle(n, g, visiting, visited)) {
                issues.add(err("SUBFLOW_CYCLE", "子流程钉版本展开后存在循环调用", "subflow"));
                return;
            }
        }
    }

    private void checkLimits(FlowDslDocument doc, List<ValidationIssue> issues) {
        if (nodesOf(doc).size() > MAX_NODES) {
            issues.add(err("LIMIT_NODE_COUNT", "节点数超过 " + MAX_NODES, "nodes"));
        }
        for (Map<String, Object> n : nodesOf(doc)) {
            if ("loop".equals(str(n.get("type")))) {
                Map<String, Object> config = map(n.get("config"));
                Object max = config.get("maxIterations");
                if (max == null) {
                    issues.add(err("LIMIT_LOOP", "Loop 必须设置最大轮数", "nodes." + str(n.get("id"))));
                }
            }
        }
        Object timeout = doc.settings() == null ? null : doc.settings().get("runTimeout");
        if (timeout != null && parseSeconds(str(timeout)) > 24 * 3600) {
            issues.add(err("LIMIT_TIMEOUT", "Run 超时超过上限", "settings.runTimeout"));
        }
    }

    private void checkPublication(FlowDslDocument doc, FlowPublishContext ctx, List<ValidationIssue> issues) {
        String kind = ctx.publicationKind();
        if (!"TOOL".equalsIgnoreCase(kind) && !"MCP".equalsIgnoreCase(kind)) {
            return;
        }
        boolean blocking = nodesOf(doc).stream().anyMatch(n -> {
            String t = str(n.get("type"));
            return "agent".equals(t) || "humancard".equalsIgnoreCase(t) || "delay".equals(t);
        });
        if (!blocking) {
            return;
        }
        long cap = ctx.toolSyncMaxSeconds();
        long maxNode = nodesOf(doc).stream()
                .map(n -> parseSeconds(str(map(n.get("config")).get("timeout"))))
                .filter(s -> s > 0)
                .max(Long::compare)
                .orElse(parseSeconds(str(doc.settings() == null ? null : doc.settings().get("runTimeout"))));
        if (maxNode > cap) {
            issues.add(err("PUB_SYNC_CAP",
                    "含 Agent/HumanCard/Delay 的 TOOL/MCP 发布超时 " + maxNode + "s 超过同步上限 " + cap + "s",
                    "settings"));
        }
    }

    private void checkQuality(FlowDslDocument doc, List<ValidationIssue> issues) {
        Map<String, Map<String, String>> ports = resolvePortTypes(doc);
        boolean anyPort = ports.values().stream()
                .flatMap(m -> m.values().stream())
                .anyMatch(t -> safeParse(t) == PortType.ANY);
        if (anyPort) {
            issues.add(warn("QUALITY_ANY", "存在 any 端口", "ports"));
        }
        for (Map<String, Object> n : nodesOf(doc)) {
            String type = str(n.get("type"));
            if (DEPRECATED.contains(type)) {
                issues.add(warn("QUALITY_DEPRECATED", "节点类型已弃用: " + type, "nodes." + str(n.get("id"))));
            }
            if (Boolean.TRUE.equals(map(n.get("config")).get("fixedOutput"))) {
                issues.add(warn("QUALITY_FIXED_OUTPUT", "存在调试用固定输出（不会进版本）", "nodes." + str(n.get("id"))));
            }
        }
    }

    private Map<String, Map<String, String>> resolvePortTypes(FlowDslDocument doc) {
        Map<String, Map<String, String>> out = new HashMap<>();
        String inputOut = "text";
        if (doc.inputs() != null && !doc.inputs().isEmpty()) {
            inputOut = str(doc.inputs().get(0).get("type"));
            if (inputOut == null) {
                inputOut = "text";
            }
        }
        String outputIn = "text";
        if (doc.outputs() != null && !doc.outputs().isEmpty()) {
            outputIn = str(doc.outputs().get(0).get("type"));
            if (outputIn == null) {
                outputIn = "text";
            }
        }
        for (Map<String, Object> n : nodesOf(doc)) {
            String id = str(n.get("id"));
            String type = str(n.get("type"));
            Map<String, String> ports = new LinkedHashMap<>();
            switch (type == null ? "" : type) {
                case "input" -> ports.put("out", inputOut);
                case "output" -> ports.put("in", outputIn);
                case "json_accept" -> {
                    ports.put("in", "json");
                    ports.put("out", "json");
                }
                case "any_gate" -> {
                    ports.put("in", "any");
                    ports.put("out", "any");
                }
                case "branch" -> {
                    ports.put("in", "any");
                    ports.put("true", "any");
                    ports.put("false", "any");
                }
                default -> {
                    ports.put("in", "any");
                    ports.put("out", "any");
                    ports.put("error", "any");
                }
            }
            out.put(id, ports);
        }
        return out;
    }

    private void validateExpr(String expr, Set<String> nodeIds, Set<String> inputs, Set<String> consts,
                              String currentNode, List<ValidationIssue> issues) {
        String body = expr.trim();
        if (body.startsWith("nodes.")) {
            String rest = body.substring("nodes.".length());
            String node = rest.contains(".") ? rest.substring(0, rest.indexOf('.')) : rest;
            if (!nodeIds.contains(node)) {
                issues.add(err("TYPE_EXPR", "表达式引用不存在的节点: " + node, "nodes." + currentNode));
            }
        } else if (body.startsWith("input.")) {
            String name = body.substring("input.".length());
            if (name.contains(".")) {
                name = name.substring(0, name.indexOf('.'));
            }
            if (!inputs.contains(name)) {
                issues.add(err("TYPE_EXPR", "表达式引用不存在的 input: " + name, "nodes." + currentNode));
            }
        } else if (body.startsWith("const.")) {
            String name = body.substring("const.".length());
            if (!consts.contains(name)) {
                issues.add(err("TYPE_EXPR", "表达式引用不存在的 const: " + name, "nodes." + currentNode));
            }
        } else if (!(body.startsWith("run.") || body.equals("item") || body.equals("index") || body.equals("iteration")
                || body.matches("[0-9].*") || body.equals("true") || body.equals("false"))) {
            // 允许简单字面量；完整 CEL 编译待 PK-00c / 越界引入 dev.cel
        }
    }

    private static List<String> collectExprs(Map<String, Object> node) {
        List<String> found = new ArrayList<>();
        walk(node.get("config"), found);
        walk(node.get("inputs"), found);
        walk(node.get("when"), found);
        return found;
    }

    private static void walk(Object o, List<String> found) {
        if (o instanceof String s) {
            Matcher m = EXPR.matcher(s);
            while (m.find()) {
                found.add(m.group(1));
            }
        } else if (o instanceof Map<?, ?> map) {
            for (Object v : map.values()) {
                walk(v, found);
            }
        } else if (o instanceof List<?> list) {
            for (Object v : list) {
                walk(v, found);
            }
        }
    }

    private static boolean hasCycle(Set<String> ids, List<Map<String, Object>> edges) {
        Map<String, List<String>> g = new HashMap<>();
        for (String id : ids) {
            g.put(id, new ArrayList<>());
        }
        for (Map<String, Object> e : edges) {
            String from = str(e.get("fromNode"));
            String to = str(e.get("toNode"));
            if (from != null && to != null && g.containsKey(from) && g.containsKey(to)) {
                g.get(from).add(to);
            }
        }
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String n : ids) {
            if (dfsCycle(n, g, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    private static boolean dfsCycle(String n, Map<String, List<String>> g, Set<String> visiting, Set<String> visited) {
        if (visiting.contains(n)) {
            return true;
        }
        if (visited.contains(n)) {
            return false;
        }
        visiting.add(n);
        for (String next : g.getOrDefault(n, List.of())) {
            if (dfsCycle(next, g, visiting, visited)) {
                return true;
            }
        }
        visiting.remove(n);
        visited.add(n);
        return false;
    }

    private static Set<String> reachableFromInput(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        String start = nodes.stream()
                .filter(n -> "input".equals(str(n.get("type"))))
                .map(n -> str(n.get("id")))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        Set<String> reach = new HashSet<>();
        if (start == null) {
            return reach;
        }
        Map<String, List<String>> g = new HashMap<>();
        for (Map<String, Object> e : edges) {
            String from = str(e.get("fromNode"));
            String to = str(e.get("toNode"));
            if (from != null && to != null) {
                g.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
            }
        }
        ArrayDeque<String> q = new ArrayDeque<>();
        q.add(start);
        while (!q.isEmpty()) {
            String cur = q.removeFirst();
            if (!reach.add(cur)) {
                continue;
            }
            q.addAll(g.getOrDefault(cur, List.of()));
        }
        return reach;
    }

    private static int maxSubgraphDepthFromRoots(FlowDslDocument doc) {
        Map<String, Object> all = doc.subgraphs();
        if (all == null || all.isEmpty()) {
            return 0;
        }
        int max = 0;
        for (Map<String, Object> n : nodesOf(doc)) {
            String child = str(map(n.get("config")).get("subgraph"));
            if (child != null && all.containsKey(child)) {
                max = Math.max(max, depthOfChain(all, child, 1, new HashSet<>()));
            }
        }
        return max;
    }

    @SuppressWarnings("unchecked")
    private static int depthOfChain(Map<String, Object> all, String id, int depth, Set<String> stack) {
        if (!stack.add(id)) {
            return depth;
        }
        Object raw = all.get(id);
        if (!(raw instanceof Map<?, ?> sg)) {
            return depth;
        }
        int max = depth;
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) sg.get("nodes");
        if (nodes != null) {
            for (Map<String, Object> n : nodes) {
                String child = str(map(n.get("config")).get("subgraph"));
                if (child != null && all.containsKey(child)) {
                    max = Math.max(max, depthOfChain(all, child, depth + 1, new HashSet<>(stack)));
                }
            }
        }
        return max;
    }

    private static List<Map<String, Object>> nodesOf(FlowDslDocument doc) {
        return doc.nodes() == null ? List.of() : doc.nodes();
    }

    private static List<Map<String, Object>> edgesOf(FlowDslDocument doc) {
        return doc.edges() == null ? List.of() : doc.edges();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static PortType safeParse(String raw) {
        try {
            return PortType.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static long parseSeconds(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        String s = raw.trim().toLowerCase();
        try {
            if (s.endsWith("ms")) {
                return Math.max(1, Long.parseLong(s.substring(0, s.length() - 2)) / 1000);
            }
            if (s.endsWith("s")) {
                return Long.parseLong(s.substring(0, s.length() - 1));
            }
            if (s.endsWith("m")) {
                return Long.parseLong(s.substring(0, s.length() - 1)) * 60;
            }
            if (s.endsWith("h")) {
                return Long.parseLong(s.substring(0, s.length() - 1)) * 3600;
            }
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static ValidationIssue err(String code, String msg, String path) {
        return new ValidationIssue(code, ValidationSeverity.ERROR, msg, path);
    }

    private static ValidationIssue warn(String code, String msg, String path) {
        return new ValidationIssue(code, ValidationSeverity.WARNING, msg, path);
    }
}
