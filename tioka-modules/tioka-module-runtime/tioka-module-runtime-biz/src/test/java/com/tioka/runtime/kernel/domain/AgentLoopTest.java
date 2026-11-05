package com.tioka.runtime.kernel.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeItemDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentLoopTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<RuntimeItemDO> items = new CopyOnWriteArrayList<>();
    private final List<String> statuses = new ArrayList<>();
    private final AtomicInteger modelCalls = new AtomicInteger();
    private ScriptedModel model;
    private RecordingSink sink;
    private AgentLoop loop;

    @BeforeEach
    void setUp() {
        items.clear();
        statuses.clear();
        modelCalls.set(0);
        model = new ScriptedModel();
        sink = new RecordingSink();
        TurnRepository turns = mock(TurnRepository.class);
        when(turns.incrementStep(anyString(), anyLong())).thenReturn(1);
        loop = new AgentLoop(model, new EchoToolPort(), sink,
                new ContextBuilder(mapper), turns, tid -> List.copyOf(items), mapper);
    }

    @Test
    void threeToolCallsThenAnswer() {
        model.script(
                tools("echo", "{\"text\":\"step1\"}"),
                tools("echo", "{\"text\":\"step2\"}"),
                tools("echo", "{\"text\":\"step3\"}"),
                text("done: step1 step2 step3"));
        LoopResult r = loop.run(ctx(20, 3));
        assertEquals(TurnStatus.COMPLETED, r.status());
        assertEquals(3, sink.toolCalls.size());
        assertEquals(3, sink.toolResults.size());
        assertEquals(1, sink.messages.size());
        assertEquals("done: step1 step2 step3", sink.messages.get(0));
    }

    @Test
    void parallelToolsKeepOrder() throws Exception {
        model.script(new LoopPorts.ModelPort.Result(null, null, List.of(
                new LoopPorts.ToolCall("c1", "slow", "{\"n\":1}"),
                new LoopPorts.ToolCall("c2", "slow", "{\"n\":2}")), 1, 1, "m"));
        model.script(text("ok"));
        long t0 = System.nanoTime();
        LoopResult r = loop.run(ctx(20, 3));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(TurnStatus.COMPLETED, r.status());
        assertEquals(List.of("c1", "c2"), sink.toolResults.stream().map(LoopPorts.ToolOutcome::callId).toList());
        assertTrue(ms < 800, "parallel expected ~300ms, was " + ms);
    }

    @Test
    void maxStepsStops() {
        model.alwaysTools("echo", "{}");
        // startStep=1,maxSteps=2 → 第 1 步给工具，第 2 步不给
        LoopResult r = loop.run(new LoopContext("thr_1", "turn_1", 1L, 2, 3, 1, c -> {}));
        assertEquals(TurnStatus.COMPLETED, r.status());
        assertTrue(statuses.contains("max_steps_reached"));
        assertEquals(1, sink.toolCalls.size());
    }

    @Test
    void repeatCallStopped() {
        model.alwaysTools("echo", "{\"x\":1}");
        LoopResult r = loop.run(ctx(20, 3));
        assertEquals(TurnStatus.COMPLETED, r.status());
        assertTrue(statuses.contains("repeat_call_stopped"));
    }

    @Test
    void toolTimeoutBecomesResult() {
        model.script(tools("hang", "{}"), text("survived"));
        LoopResult r = loop.run(ctx(20, 3));
        assertEquals(TurnStatus.COMPLETED, r.status());
        assertFalse(sink.toolResults.get(0).ok());
        assertEquals("survived", sink.messages.get(0));
    }

    @Test
    void badArgumentsJson() {
        model.script(tools("echo", "not-json"), text("ok"));
        LoopResult r = loop.run(ctx(20, 3));
        assertEquals(TurnStatus.COMPLETED, r.status());
        assertFalse(sink.toolResults.get(0).ok());
        assertTrue(sink.toolResults.get(0).output().contains("不是合法 JSON"));
    }

    private LoopContext ctx(int maxSteps, int repeat) {
        return new LoopContext("thr_1", "turn_1", 1L, maxSteps, repeat, 0, c -> {});
    }

    private static LoopPorts.ModelPort.Result tools(String name, String args) {
        return new LoopPorts.ModelPort.Result(null, null,
                List.of(new LoopPorts.ToolCall("call_" + name + "_" + System.nanoTime(), name, args)),
                1, 1, "m");
    }

    private static LoopPorts.ModelPort.Result text(String t) {
        return new LoopPorts.ModelPort.Result(t, null, List.of(), 1, 1, "m");
    }

    private final class ScriptedModel implements LoopPorts.ModelPort {
        private final List<Result> script = new ArrayList<>();
        private Result always;

        void script(Result... rs) {
            script.addAll(List.of(rs));
        }

        void alwaysTools(String name, String args) {
            always = tools(name, args);
        }

        @Override
        public Result chat(LoopContext ctx, List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                           java.util.function.Consumer<Delta> onDelta) {
            modelCalls.incrementAndGet();
            if (tools.isEmpty() && always != null) {
                // 强制最终步：仍返回工具 → 由 AgentLoop 写 max_steps / 或空工具后给答案
                return always;
            }
            if (!script.isEmpty()) {
                return script.remove(0);
            }
            if (always != null) {
                return always;
            }
            return text("fallback");
        }
    }

    private final class EchoToolPort implements LoopPorts.ToolPort {
        @Override
        public List<Map<String, Object>> describe(LoopContext ctx) {
            return List.of(Map.of("type", "function", "function", Map.of("name", "echo")));
        }

        @Override
        public List<LoopPorts.ToolOutcome> invokeAll(LoopContext ctx, List<LoopPorts.ToolCall> calls) {
            // 保序并行：各 call 独立执行，按原顺序收集
            List<java.util.concurrent.CompletableFuture<LoopPorts.ToolOutcome>> futures = new ArrayList<>();
            for (LoopPorts.ToolCall c : calls) {
                futures.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> invokeOne(c)));
            }
            return futures.stream().map(java.util.concurrent.CompletableFuture::join).toList();
        }

        private LoopPorts.ToolOutcome invokeOne(LoopPorts.ToolCall c) {
            long t0 = System.currentTimeMillis();
            if ("hang".equals(c.name())) {
                return new LoopPorts.ToolOutcome(c.callId(), c.name(), false, "timeout", 300);
            }
            try {
                mapper.readTree(c.argumentsJson());
            } catch (Exception e) {
                return new LoopPorts.ToolOutcome(c.callId(), c.name(), false,
                        "arguments 不是合法 JSON：" + e.getMessage(), 0);
            }
            if ("slow".equals(c.name())) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new LoopPorts.ToolOutcome(c.callId(), c.name(), true, "ok:" + c.argumentsJson(),
                    System.currentTimeMillis() - t0);
        }
    }

    private final class RecordingSink implements LoopPorts.ItemSink {
        final List<String> messages = new ArrayList<>();
        final List<LoopPorts.ToolCall> toolCalls = new ArrayList<>();
        final List<LoopPorts.ToolOutcome> toolResults = new ArrayList<>();

        @Override
        public void delta(LoopContext ctx, LoopPorts.ModelPort.Delta delta) {}

        @Override
        public void completeMessage(LoopContext ctx, int step, String text) {
            messages.add(text);
            appendItem(ctx, step, ItemType.MESSAGE.wire(), "assistant", Map.of("text", text));
        }

        @Override
        public void complete(LoopContext ctx, int step, String type, String role, String contentJson) {
            appendItem(ctx, step, type, role, contentJson);
        }

        @Override
        public void toolCall(LoopContext ctx, int step, LoopPorts.ToolCall call) {
            toolCalls.add(call);
            appendItem(ctx, step, ItemType.TOOL_CALL.wire(), "assistant",
                    Map.of("callId", call.callId(), "name", call.name(), "arguments", call.argumentsJson()));
        }

        @Override
        public void toolResult(LoopContext ctx, int step, LoopPorts.ToolOutcome outcome) {
            toolResults.add(outcome);
            appendItem(ctx, step, ItemType.TOOL_RESULT.wire(), "tool",
                    Map.of("callId", outcome.callId(), "name", outcome.name(),
                            "ok", outcome.ok(), "output", outcome.output()));
        }

        @Override
        public void status(LoopContext ctx, int step, String kind) {
            statuses.add(kind);
            appendItem(ctx, step, ItemType.STATUS.wire(), "system", Map.of("kind", kind));
        }

        private void appendItem(LoopContext ctx, int step, String type, String role, Object content) {
            RuntimeItemDO item = new RuntimeItemDO();
            item.setSessionId(ctx.threadId());
            item.setTurnId(ctx.turnId());
            item.setStep(step);
            item.setType(type);
            item.setRole(role);
            item.setSeq((long) items.size() + 1);
            try {
                item.setContentJson(content instanceof String s ? s : mapper.writeValueAsString(content));
            } catch (Exception e) {
                item.setContentJson("{}");
            }
            item.setDiscarded(false);
            items.add(item);
        }
    }
}
