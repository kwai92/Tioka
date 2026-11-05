package com.tioka.flow.application;

import com.tioka.flow.application.exec.NodeExecContext;
import com.tioka.flow.application.exec.NodeExecutor;
import com.tioka.flow.application.exec.NodeExecutorRegistry;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/**
 * R5-01：真 DAG 调度 — 拓扑分层 / Branch 选路 / 有界并行 / 失败落 FAILED。
 */
@Component
public class FlowEngine {
    private static final int PARALLELISM = 4;
    private final NodeExecutorRegistry registry;
    private final ExecutorService pool;

    public FlowEngine(NodeExecutorRegistry registry) {
        this.registry = registry;
        this.pool = Executors.newFixedThreadPool(PARALLELISM, r -> {
            Thread t = new Thread(r, "flow-engine");
            t.setDaemon(true);
            return t;
        });
    }

    public EngineResult run(String runId, Map<String, Object> graph, Map<String, Object> input) {
        FlowGraphValidator.validate(graph, FlowService.NODE_TYPES);
        CredentialPolicy.assertNoWildKeys(graph);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.getOrDefault("nodes", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) graph.getOrDefault("edges", List.of());

        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            byId.put(String.valueOf(node.get("id")), node);
        }

        NodeExecContext ctx = new NodeExecContext(runId, input);
        List<Map<String, Object>> trace = Collections.synchronizedList(new ArrayList<>());

        try {
            if (edges == null || edges.isEmpty()) {
                runSequential(nodes, ctx, trace);
            } else {
                runDag(byId, edges, ctx, trace);
            }
            String status = ctx.suspended() ? "SUSPENDED" : "COMPLETED";
            Object output = ctx.terminalOutput();
            if (output == null && !trace.isEmpty()) {
                output = lastMeaningfulOutput(trace);
            }
            return new EngineResult(status, output, List.copyOf(trace), null);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", msg);
            err.put("type", "engine");
            trace.add(err);
            return new EngineResult("FAILED", null, List.copyOf(trace), msg);
        }
    }

    private void runSequential(List<Map<String, Object>> nodes, NodeExecContext ctx,
                               List<Map<String, Object>> trace) throws Exception {
        Object acc = ctx.runInput();
        for (Map<String, Object> node : nodes) {
            acc = execOne(ctx, node, acc, null, trace);
        }
    }

    private void runDag(Map<String, Map<String, Object>> byId, List<Map<String, Object>> edges,
                        NodeExecContext ctx, List<Map<String, Object>> trace) throws Exception {
        Map<String, List<Map<String, Object>>> outEdges = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> preds = new HashMap<>();
        for (String id : byId.keySet()) {
            outEdges.put(id, new ArrayList<>());
            indegree.put(id, 0);
            preds.put(id, new ArrayList<>());
        }
        for (Map<String, Object> e : edges) {
            String from = String.valueOf(e.get("from"));
            String to = String.valueOf(e.get("to"));
            outEdges.get(from).add(e);
            indegree.merge(to, 1, Integer::sum);
            preds.get(to).add(from);
        }

        Map<String, Object> outputs = new ConcurrentHashMap<>();
        Deque<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : indegree.entrySet()) {
            if (e.getValue() == 0) ready.add(e.getKey());
        }
        if (ready.isEmpty() && !byId.isEmpty()) {
            throw new IllegalStateException("DAG 无起始节点（可能成环）");
        }

        Set<String> completed = ConcurrentHashMap.newKeySet();
        while (!ready.isEmpty()) {
            List<String> wave = new ArrayList<>();
            while (!ready.isEmpty()) wave.add(ready.poll());
            String parallelGroup = wave.size() > 1 ? "pg_" + UUID.randomUUID().toString().substring(0, 8) : null;

            List<WaveItem> items = executeWave(wave, byId, preds, outputs, ctx, parallelGroup, trace);
            for (WaveItem item : items) {
                outputs.put(item.id(), item.output());
                ctx.putOutput(item.id(), item.output());
                completed.add(item.id());
            }
            for (WaveItem item : items) {
                Map<String, Object> node = byId.get(item.id());
                String type = String.valueOf(node.get("type"));
                boolean branchPass = branchValue(item.output());
                for (Map<String, Object> e : outEdges.getOrDefault(item.id(), List.of())) {
                    if ("Branch".equals(type) && !branchEdgeMatches(e, branchPass)) {
                        continue;
                    }
                    String to = String.valueOf(e.get("to"));
                    int left = indegree.merge(to, -1, Integer::sum);
                    if (left == 0) {
                        ready.add(to);
                    }
                }
            }
        }
    }

    private List<WaveItem> executeWave(List<String> wave, Map<String, Map<String, Object>> byId,
                                       Map<String, List<String>> preds, Map<String, Object> outputs,
                                       NodeExecContext ctx, String parallelGroup,
                                       List<Map<String, Object>> trace) throws Exception {
        if (wave.size() == 1) {
            String id = wave.get(0);
            Object in = resolveInput(id, preds, outputs, ctx);
            Object out = execOne(ctx, byId.get(id), in, parallelGroup, trace);
            return List.of(new WaveItem(id, out));
        }
        List<Callable<WaveItem>> tasks = new ArrayList<>();
        for (String id : wave) {
            Object in = resolveInput(id, preds, outputs, ctx);
            tasks.add(() -> new WaveItem(id, execOne(ctx, byId.get(id), in, parallelGroup, trace)));
        }
        List<Future<WaveItem>> futures = pool.invokeAll(tasks);
        List<WaveItem> items = new ArrayList<>();
        for (Future<WaveItem> f : futures) {
            items.add(f.get());
        }
        return items;
    }

    private static boolean branchEdgeMatches(Map<String, Object> edge, boolean branchPass) {
        String when = String.valueOf(edge.getOrDefault("when", "null"));
        if ("null".equals(when)) return true;
        return ("true".equals(when) && branchPass) || ("false".equals(when) && !branchPass);
    }

    private static boolean branchValue(Object output) {
        if (output instanceof Map<?, ?> m && m.containsKey("branch")) {
            Object b = m.get("branch");
            if (b instanceof Boolean bool) return bool;
            return Boolean.parseBoolean(String.valueOf(b));
        }
        return false;
    }

    private Object resolveInput(String id, Map<String, List<String>> preds,
                                Map<String, Object> outputs, NodeExecContext ctx) {
        List<String> ps = preds.getOrDefault(id, List.of());
        List<Object> available = new ArrayList<>();
        for (String p : ps) {
            if (outputs.containsKey(p)) available.add(outputs.get(p));
        }
        if (available.isEmpty()) return ctx.runInput();
        if (available.size() == 1) return available.get(0);
        Map<String, Object> merged = new LinkedHashMap<>(ctx.runInput());
        for (int i = 0; i < ps.size(); i++) {
            Object o = outputs.get(ps.get(i));
            if (o instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    merged.put(String.valueOf(e.getKey()), e.getValue());
                }
            } else if (o != null) {
                merged.put(ps.get(i), o);
            }
        }
        return merged;
    }

    private Object execOne(NodeExecContext ctx, Map<String, Object> node, Object input,
                           String parallelGroup, List<Map<String, Object>> trace) throws Exception {
        String type = String.valueOf(node.get("type"));
        String id = String.valueOf(node.get("id"));
        NodeExecutor executor = registry.require(type);
        Object output;
        try {
            output = executor.execute(ctx, node, input);
        } catch (Exception e) {
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("id", id);
            fail.put("type", type);
            fail.put("input", input == null ? Map.of() : input);
            fail.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            if (parallelGroup != null) fail.put("parallelGroup", parallelGroup);
            trace.add(fail);
            throw e;
        }
        ctx.putOutput(id, output);
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", id);
        step.put("type", type);
        step.put("input", input == null ? Map.of() : input);
        step.put("output", output == null ? Map.of() : output);
        if (parallelGroup != null) step.put("parallelGroup", parallelGroup);
        trace.add(step);
        return output;
    }

    private static Object lastMeaningfulOutput(List<Map<String, Object>> trace) {
        Object lastIoOrOutput = null;
        Object lastAny = null;
        for (Map<String, Object> step : trace) {
            if (!step.containsKey("output")) continue;
            lastAny = step.get("output");
            String type = String.valueOf(step.get("type"));
            if ("Output".equals(type) || "IO".equals(type)) {
                lastIoOrOutput = lastAny;
            }
        }
        return lastIoOrOutput != null ? lastIoOrOutput : lastAny;
    }

    private record WaveItem(String id, Object output) {}

    public record EngineResult(String status, Object output, List<Map<String, Object>> trace, String error) {}
}
