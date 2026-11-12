package com.tioka.runtime.kernel.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.runtime.api.context.ContextRenderContext;
import com.tioka.runtime.api.context.ContextSegmentProvider;
import com.tioka.runtime.api.context.ContextZone;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** CTX-01：四区接线、D 区按 Turn 冻结、预算溢出与开关隔离。 */
class ContextAssemblyTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void disabledBuilderKeepsM2Behaviour() {
        ContextBuilder builder = new ContextBuilder(MAPPER,
                new ContextSegmentRegistry(List.of(provider(ContextZone.D_TURN_TAIL, "注入内容"))),
                false, 12_000);
        List<Map<String, Object>> messages = builder.build(items(),
                ContextRenderContext.of("turn_1", "thr_1", "ws_1", "av_1", "问题"));
        assertEquals(1, messages.size(), "开关关闭时不得插入任何额外消息");
        assertEquals("user", messages.get(0).get("role"));
    }

    @Test
    void dZoneIsFrozenPerTurnAndPrefixHashStable() {
        AtomicInteger renders = new AtomicInteger();
        ContextSegmentProvider d = new ContextSegmentProvider() {
            @Override public String segmentName() { return "knowledge.auto_inject"; }
            @Override public ContextZone zone() { return ContextZone.D_TURN_TAIL; }
            @Override public int order() { return 90; }
            @Override public String render(ContextRenderContext ctx) {
                renders.incrementAndGet();
                return "检索结果[" + ctx.query() + "]";
            }
        };
        ContextBuilder builder = new ContextBuilder(MAPPER,
                new ContextSegmentRegistry(List.of(d)), true, 12_000);
        ContextRenderContext ctx = ContextRenderContext.of("turn_1", "thr_1", "ws_1", "av_1", "问题");

        List<Map<String, Object>> first = builder.build(items(), ctx);
        String hash1 = builder.frozenTailHash("turn_1");
        // 同一 Turn 第二步：query 变化也不重新渲染 D 区
        List<Map<String, Object>> second = builder.build(items(),
                ContextRenderContext.of("turn_1", "thr_1", "ws_1", "av_1", "完全不同的一步"));
        String hash2 = builder.frozenTailHash("turn_1");

        assertEquals(1, renders.get(), "同一 Turn 的 D 区只允许渲染一次");
        assertEquals(hash1, hash2, "同一 Turn 多步之间前缀哈希必须不变");
        assertFalse(hash1.isBlank());
        assertEquals(first.get(first.size() - 1), second.get(second.size() - 1),
                "冻结后的本轮尾部消息应完全一致");
        assertTrue(String.valueOf(first.get(first.size() - 1).get("content")).contains("检索结果[问题]"));

        // 另一个 Turn 独立渲染
        builder.build(items(), ContextRenderContext.of("turn_2", "thr_1", "ws_1", "av_1", "问题"));
        assertEquals(2, renders.get());
        assertNotEquals(hash1, builder.frozenTailHash(""));
    }

    @Test
    void aZoneComesBeforeHistoryAndDZoneAtTail() {
        ContextSegmentProvider a = provider(ContextZone.A_STABLE_PREFIX, "稳定前缀");
        ContextSegmentProvider d = provider(ContextZone.D_TURN_TAIL, "本轮尾部");
        ContextBuilder builder = new ContextBuilder(MAPPER,
                new ContextSegmentRegistry(List.of(d, a)), true, 12_000);
        List<Map<String, Object>> out = builder.build(items(),
                ContextRenderContext.of("turn_3", "thr_1", "ws_1", "av_1", "问题"));
        assertEquals(3, out.size());
        assertTrue(String.valueOf(out.get(0).get("content")).contains("稳定前缀"));
        assertEquals("user", out.get(1).get("role"));
        assertTrue(String.valueOf(out.get(2).get("content")).contains("本轮尾部"));
    }

    @Test
    void overflowIsExplicitNotSilentlyTruncated() {
        ContextBuilder builder = new ContextBuilder(MAPPER,
                new ContextSegmentRegistry(List.of(provider(ContextZone.A_STABLE_PREFIX, "x".repeat(300)))),
                true, 10);
        BizException ex = assertThrows(BizException.class, () -> builder.build(items(),
                ContextRenderContext.of("turn_4", "thr_1", "ws_1", "av_1", "问题")));
        assertTrue(ex.getMessage().contains("超出有效预算"), ex.getMessage());
    }

    @Test
    void dZoneOverflowIsExplicitNotSilentlyTruncated() {
        ContextBuilder builder = new ContextBuilder(MAPPER,
                new ContextSegmentRegistry(List.of(provider(ContextZone.D_TURN_TAIL, "x".repeat(300)))),
                true, 10);
        BizException ex = assertThrows(BizException.class, () -> builder.build(items(),
                ContextRenderContext.of("turn_6", "thr_1", "ws_1", "av_1", "问题")));
        assertTrue(ex.getMessage().contains("超出有效预算"), ex.getMessage());
        assertEquals(com.tioka.framework.error.ErrorCode.CONTEXT_OVERFLOW, ex.getErrorCode());
        assertTrue(builder.frozenTailText("turn_6").contains("x"),
                "D 区超预算须显式报错，不得静默截断冻结文本");
    }

    @Test
    void queryForTurnUsesFirstMessageOfThisTurnNotSteer() {
        RuntimeItemDO older = message("turn_old", "旧问题");
        RuntimeItemDO first = message("turn_7", "本轮真正的问题");
        RuntimeItemDO steer = message("turn_7", "继续");
        assertEquals("本轮真正的问题",
                ContextBuilder.queryForTurn(List.of(older, first, steer), MAPPER, "turn_7"),
                "多步里的 steer 不得污染自动注入的检索问题");
        assertEquals("旧问题",
                ContextBuilder.queryForTurn(List.of(older), MAPPER, "turn_missing"),
                "无匹配时退回全会话首条 user");
    }

    @Test
    void providerFailureDoesNotBreakTurn() {
        ContextSegmentProvider broken = new ContextSegmentProvider() {
            @Override public String segmentName() { return "broken"; }
            @Override public ContextZone zone() { return ContextZone.D_TURN_TAIL; }
            @Override public int order() { return 1; }
            @Override public String render(ContextRenderContext ctx) {
                throw new IllegalStateException("downstream unavailable");
            }
        };
        ContextBuilder builder = new ContextBuilder(MAPPER,
                new ContextSegmentRegistry(List.of(broken)), true, 12_000);
        assertEquals(1, builder.build(items(),
                ContextRenderContext.of("turn_5", "thr_1", "ws_1", "av_1", "问题")).size());
    }

    private static ContextSegmentProvider provider(ContextZone zone, String text) {
        return new ContextSegmentProvider() {
            @Override public String segmentName() { return "test." + zone.name(); }
            @Override public ContextZone zone() { return zone; }
            @Override public int order() { return 1; }
            @Override public String render(ContextRenderContext ctx) { return text; }
        };
    }

    private static RuntimeItemDO message(String turnId, String text) {
        RuntimeItemDO item = new RuntimeItemDO();
        item.setType(ItemType.MESSAGE.wire());
        item.setRole("user");
        item.setTurnId(turnId);
        item.setContentJson("{\"text\":\"" + text + "\"}");
        item.setStep(0);
        return item;
    }

    private static List<RuntimeItemDO> items() {
        RuntimeItemDO item = new RuntimeItemDO();
        item.setType(ItemType.MESSAGE.wire());
        item.setRole("user");
        item.setContentJson("{\"text\":\"你好\"}");
        item.setStep(0);
        return List.of(item);
    }
}
