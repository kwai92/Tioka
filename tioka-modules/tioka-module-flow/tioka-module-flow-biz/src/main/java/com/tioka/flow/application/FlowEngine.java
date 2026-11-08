package com.tioka.flow.application;

import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.flow.application.exec.NodeExecContext;
import com.tioka.flow.application.exec.NodeExecutor;
import com.tioka.flow.application.exec.NodeExecutorRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/**
 * R5-01 / FLW-01：真 DAG 调度 — 边状态 PENDING/LIVE/DEAD、Branch 未选路径 SKIPPED 传播、有界并行。
 */
@Component
public class FlowEngine {
    private static final int PARALLELISM = 4;
    private final NodeExecutorRegistry registry;
    private final ObjectProvider<PrincipalHolder> principals;
    private final ExecutorService pool;

    public FlowEngine(NodeExecutorRegistry registry,
                      ObjectProvider<PrincipalHolder> principals) {
        this.registry = registry;
        this.principals = principals;
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

        NodeExecContext ctx = new NodeExecContext(runId, resolveRunAs(), input);
        List<Map<String, Object>> trace = Collections.synchronizedList(new ArrayList<>());

        try {
            if (edges == null || edges.isEmpty()) {
                runSequential(nodes, ctx, trace);
            } else {
                runDag(byId, edges, ctx, trace);
            }
            String status = ctx.suspended() ? "SUSPENDED" : "COMPLETED";
            Object output = resolveRunOutput(ctx, trace);
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
        Map<String, List<DagEdge>> outEdges = new HashMap<>();
        Map<String, List<DagEdge>> inEdges = new HashMap<>();
        for (String id : byId.keySet()) {
            outEdges.put(id, new ArrayList<>());
            inEdges.put(id, new ArrayList<>());
        }
        for (Map<String, Object> raw : edges) {
            String from = String.valueOf(raw.get("from"));
            String to = String.valueOf(raw.get("to"));
            if (!byId.containsKey(from) || !byId.containsKey(to)) {
                continue;
            }
            DagEdge edge = new DagEdge(from, to, raw);
            outEdges.get(from).add(edge);
            inEdges.get(to).add(edge);
        }

        Map<String, Object> outputs = new ConcurrentHashMap<>();
        Deque<String> ready = new ArrayDeque<>();
        Set<String> settled = new HashSet<>();

        for (String id : byId.keySet()) {
            if (inEdges.get(id).isEmpty()) {
                ready.add(id);
                settled.add(id);
            }
        }
        if (ready.isEmpty() && !byId.isEmpty()) {
            throw new IllegalStateException("DAG 无起始节点（可能成环）");
        }

        while (!ready.isEmpty()) {
            List<String> wave = new ArrayList<>();
            while (!ready.isEmpty()) {
                wave.add(ready.poll());
            }
            String parallelGroup = wave.size() > 1 ? Ids.newId("pg") : null;

            List<WaveItem> items = executeWave(wave, byId, inEdges, outputs, ctx, parallelGroup, trace);
            for (WaveItem item : items) {
                outputs.put(item.id(), item.output());
                ctx.putOutput(item.id(), item.output());
            }
            for (WaveItem item : items) {
                onNodeFinished(item.id(), item.output(), byId, outEdges, inEdges, settled, ready, trace);
            }
        }
    }

    private void onNodeFinished(String nodeId, Object output,
                                Map<String, Map<String, Object>> byId,
                                Map<String, List<DagEdge>> outEdges,
                                Map<String, List<DagEdge>> inEdges,
                                Set<String> settled, Deque<String> ready,
                                List<Map<String, Object>> trace) {
        Map<String, Object> node = byId.get(nodeId);
        String type = String.valueOf(node.get("type"));
        boolean branchPass = branchValue(output);
        for (DagEdge e : outEdges.getOrDefault(nodeId, List.of())) {
            if ("Branch".equals(type) && !branchEdgeMatches(e.raw(), branchPass)) {
                e.state = EdgeState.DEAD;
            } else {
                e.state = EdgeState.LIVE;
            }
            settle(e.to(), byId, outEdges, inEdges, settled, ready, trace);
        }
    }

    private void onNodeSkipped(String nodeId,
                               Map<String, Map<String, Object>> byId,
                               Map<String, List<DagEdge>> outEdges,
                               Map<String, List<DagEdge>> inEdges,
                               Set<String> settled, Deque<String> ready,
                               List<Map<String, Object>> trace) {
        for (DagEdge e : outEdges.getOrDefault(nodeId, List.of())) {
            e.state = EdgeState.DEAD;
            settle(e.to(), byId, outEdges, inEdges, settled, ready, trace);
        }
    }

    private void settle(String nodeId,
                        Map<String, Map<String, Object>> byId,
                        Map<String, List<DagEdge>> outEdges,
                        Map<String, List<DagEdge>> inEdges,
                        Set<String> settled, Deque<String> ready,
                        List<Map<String, Object>> trace) {
        if (settled.contains(nodeId)) {
            return;
        }
        List<DagEdge> ins = inEdges.getOrDefault(nodeId, List.of());
        for (DagEdge e : ins) {
            if (e.state == EdgeState.PENDING) {
                return;
            }
        }
        settled.add(nodeId);
        boolean allDead = !ins.isEmpty();
        for (DagEdge e : ins) {
            if (e.state != EdgeState.DEAD) {
                allDead = false;
                break;
            }
        }
        if (allDead) {
            recordSkipped(byId.get(nodeId), trace);
            onNodeSkipped(nodeId, byId, outEdges, inEdges, settled, ready, trace);
        } else {
            ready.add(nodeId);
        }
    }

    private void recordSkipped(Map<String, Object> node, List<Map<String, Object>> trace) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", String.valueOf(node.get("id")));
        step.put("type", String.valueOf(node.get("type")));
        step.put("status", "SKIPPED");
        step.put("input", Map.of());
        step.put("output", Map.of());
        trace.add(step);
    }

    private List<WaveItem> executeWave(List<String> wave, Map<String, Map<String, Object>> byId,
                                       Map<String, List<DagEdge>> inEdges, Map<String, Object> outputs,
                                       NodeExecContext ctx, String parallelGroup,
                                       List<Map<String, Object>> trace) throws Exception {
        if (wave.size() == 1) {
            String id = wave.get(0);
            Object in = resolveInput(id, inEdges, outputs, ctx);
            Object out = execOne(ctx, byId.get(id), in, parallelGroup, trace);
            return List.of(new WaveItem(id, out));
        }
        List<Callable<WaveItem>> tasks = new ArrayList<>();
        for (String id : wave) {
            Object in = resolveInput(id, inEdges, outputs, ctx);
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

    /** 只合并 LIVE 入边前驱输出；多前驱时保持既有扁平合并规则。 */
    private Object resolveInput(String id, Map<String, List<DagEdge>> inEdges,
                                Map<String, Object> outputs, NodeExecContext ctx) {
        List<String> livePreds = new ArrayList<>();
        for (DagEdge e : inEdges.getOrDefault(id, List.of())) {
            if (e.state == EdgeState.LIVE && !livePreds.contains(e.from())) {
                livePreds.add(e.from());
            }
        }
        List<Object> available = new ArrayList<>();
        for (String p : livePreds) {
            if (outputs.containsKey(p)) {
                available.add(outputs.get(p));
            }
        }
        if (available.isEmpty()) {
            return ctx.runInput();
        }
        if (available.size() == 1) {
            return available.get(0);
        }
        Map<String, Object> merged = new LinkedHashMap<>(ctx.runInput());
        for (String p : livePreds) {
            Object o = outputs.get(p);
            if (o instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    merged.put(String.valueOf(e.getKey()), e.getValue());
                }
            } else if (o != null) {
                merged.put(p, o);
            }
        }
        return merged;
    }

    private static Object resolveRunOutput(NodeExecContext ctx, List<Map<String, Object>> trace) {
        boolean outputSkipped = false;
        for (Map<String, Object> step : trace) {
            if ("Output".equals(String.valueOf(step.get("type")))
                    && "SKIPPED".equals(String.valueOf(step.get("status")))) {
                outputSkipped = true;
                break;
            }
        }
        if (outputSkipped) {
            return null;
        }
        Object output = ctx.terminalOutput();
        if (output == null && !trace.isEmpty()) {
            output = lastMeaningfulOutput(trace);
        }
        return output;
    }

    private Object execOne(NodeExecContext ctx, Map<String, Object> node, Object input,
                           String parallelGroup, List<Map<String, Object>> trace) throws Exception {
        String type = String.valueOf(node.get("type"));
        String id = String.valueOf(node.get("id"));
        String nodeRunId = Ids.newId("fnr");
        ctx.beginNodeRun(nodeRunId);
        NodeExecutor executor = registry.require(type);
        Object output;
        try {
            output = executor.execute(ctx, node, input);
        } catch (Exception e) {
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("id", id);
            fail.put("type", type);
            fail.put("nodeRunId", nodeRunId);
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
        step.put("nodeRunId", nodeRunId);
        step.put("input", input == null ? Map.of() : input);
        step.put("output", output == null ? Map.of() : output);
        if (parallelGroup != null) step.put("parallelGroup", parallelGroup);
        trace.add(step);
        return output;
    }

    private Principal resolveRunAs() {
        PrincipalHolder holder = principals == null ? null : principals.getIfAvailable();
        if (holder == null) {
            return SystemPrincipals.system();
        }
        return holder.find().orElse(SystemPrincipals.system());
    }

    private static Object lastMeaningfulOutput(List<Map<String, Object>> trace) {
        Object lastIoOrOutput = null;
        Object lastAny = null;
        for (Map<String, Object> step : trace) {
            if ("SKIPPED".equals(String.valueOf(step.get("status")))) {
                continue;
            }
            if (!step.containsKey("output")) {
                continue;
            }
            lastAny = step.get("output");
            String type = String.valueOf(step.get("type"));
            if ("Output".equals(type) || "IO".equals(type)) {
                lastIoOrOutput = lastAny;
            }
        }
        return lastIoOrOutput != null ? lastIoOrOutput : lastAny;
    }

    private enum EdgeState { PENDING, LIVE, DEAD }

    private static final class DagEdge {
        private final String from;
        private final String to;
        private final Map<String, Object> raw;
        private EdgeState state = EdgeState.PENDING;

        private DagEdge(String from, String to, Map<String, Object> raw) {
            this.from = from;
            this.to = to;
            this.raw = raw;
        }

        private String from() { return from; }
        private String to() { return to; }
        private Map<String, Object> raw() { return raw; }
    }

    private record WaveItem(String id, Object output) {}

    public record EngineResult(String status, Object output, List<Map<String, Object>> trace, String error) {}
}
