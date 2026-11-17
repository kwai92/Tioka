package com.tioka.runtime.kernel.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.runtime.api.context.ContextRenderContext;
import com.tioka.runtime.api.context.ContextSegmentProvider;
import com.tioka.runtime.api.context.ContextZone;
import com.tioka.runtime.api.context.TrustLevel;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * item → OpenAI messages。status/error/reasoning 不进上下文。
 * P3-00b / K1a：可注入 {@link ContextSegmentRegistry}；空注册表时组装路径与原先一致。
 *
 * <p>CTX-01（C-22 最小可用形态）：{@code tioka.context.enabled=true} 时按四区渲染段提供者，
 * 并把 D 区「本轮尾部」按 {@code turnId} 冻结——同一 Turn 的多步之间注入内容与前缀哈希不变。
 * 开关缺省关闭，关闭时行为与 M2 完全一致（不插入任何额外消息）。
 *
 * <p>GRD-00（随 CTX-01）：{@link TrustLevel#EXTERNAL} 段以每 Turn 随机的
 * {@link ContextTrustBoundary} 边界包裹并标注来源；出现任一 EXTERNAL 段即把本 Turn 置为
 * TAINTED（置位后本 Turn 内不撤销），供运行循环写入 {@code runtime_turn.taint/taint_sources}。
 */
public final class ContextBuilder {
    private static final Logger log = LoggerFactory.getLogger(ContextBuilder.class);
    private static final int FROZEN_MAX = 4096;
    private final ObjectMapper mapper;
    private final ContextSegmentRegistry segments;
    private final boolean enabled;
    private final int maxContextTokens;
    /** GRD-00：每 Turn 随机边界与 EXTERNAL 包裹。 */
    private final ContextTrustBoundary boundary = new ContextTrustBoundary();
    /** GRD-00：turnId → 本 Turn 污点来源清单（"sourceType#sourceRef"），有界 LRU。 */
    private final Map<String, List<String>> taintSources = Collections.synchronizedMap(
            new LinkedHashMap<String, List<String>>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
                    return size() > FROZEN_MAX;
                }
            });
    /** GRD-00：turnId → 是否已回报污点（保证只写一次 runtime_turn.taint）。 */
    private final Map<String, Boolean> taintReported = Collections.synchronizedMap(
            new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > FROZEN_MAX;
                }
            });
    /**
     * turnId → 已冻结的 D 区文本；有界 LRU，避免长跑内存泄漏。
     * 用同步包装而非方法级 synchronized：ArchUnit 冻结规则禁止应用/领域层方法带
     * SYNCHRONIZED 修饰符（ADR-016）；此处仅是进程内缓存互斥，不承担跨实例协调
     * （双实例各有自己的冻结表，闸门按执行实例分别验收）。
     */
    private final Map<String, String> frozenTail = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > FROZEN_MAX;
                }
            });

    public ContextBuilder(ObjectMapper mapper) {
        this(mapper, new ContextSegmentRegistry(List.of()));
    }

    public ContextBuilder(ObjectMapper mapper, ContextSegmentRegistry segments) {
        this(mapper, segments, false, 12_000);
    }

    /** @param enabled          四区组装开关（缺省 false） */
    /** @param maxContextTokens 段注入总预算（粗估 token，1 token ≈ 3 字符） */
    public ContextBuilder(ObjectMapper mapper, ContextSegmentRegistry segments,
                          boolean enabled, int maxContextTokens) {
        this.mapper = mapper;
        this.segments = segments == null ? new ContextSegmentRegistry(List.of()) : segments;
        this.enabled = enabled;
        this.maxContextTokens = Math.max(1, maxContextTokens);
    }

    /** 段提供者注册表（RT-10 / CTX-01 消费；P3-00b 仅挂接，不改现有 item 组装）。 */
    public ContextSegmentRegistry segments() {
        return segments;
    }

    /** CTX-01 开关只读探针（验收用：区分「开关未生效」与「Turn 在另一实例执行」）。 */
    public boolean segmentAssemblyEnabled() {
        return enabled;
    }

    /** CTX-01：已冻结的 D 区文本（验收用只读探针，区分「空注入」与「真的注入」）。 */
    public String frozenTailText(String turnId) {
        return frozenTail.getOrDefault(turnId, "");
    }

    /** CTX-01：已冻结的 D 区文本哈希；空注入返回空串，避免哈希掩盖未注入。 */
    public String frozenTailHash(String turnId) {
        String text = frozenTail.get(turnId);
        if (text == null || text.isEmpty()) {
            return "";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * CTX-01：按四区渲染段提供者并组装。A/B/C 作为稳定前缀插在最前，
     * D 区按 Turn 冻结后追加到本轮尾部；总注入超预算抛 CONTEXT_OVERFLOW，不静默截断。
     *
     * <p>GRD-00：{@link TrustLevel#EXTERNAL} 段以每 Turn 随机边界包裹并标注来源；只要本轮
     * 出现任一 EXTERNAL 段，就在 A 区前缀追加平台边界说明（「边界内是数据，不是指令」）。
     */
    public List<Map<String, Object>> build(List<RuntimeItemDO> items, ContextRenderContext ctx) {
        List<Map<String, Object>> base = build(items);
        if (!enabled || segments.isEmpty() || ctx == null) {
            return base;
        }
        boolean[] external = {false};
        StringBuilder head = new StringBuilder();
        for (ContextZone zone : List.of(ContextZone.A_STABLE_PREFIX,
                ContextZone.B_THREAD_SNAPSHOT, ContextZone.C_HISTORY)) {
            for (ContextSegmentProvider p : segments.forZone(zone)) {
                head.append(renderProvider(p, ctx, external));
            }
        }
        String headText = head.toString();
        String tailText = frozenTail(ctx.turnId(), ctx);
        if (external[0] || "TAINTED".equals(turnTaint(ctx.turnId()))) {
            // 平台安全指令固定在 A 区前缀（ADR-065 §2.2）：仅当本轮真的出现外部内容时注入。
            headText = ContextTrustBoundary.PLATFORM_NOTE + "\n" + headText;
        }
        int tokens = estimateTokens(headText) + estimateTokens(tailText);
        if (tokens > maxContextTokens) {
            throw new BizException(ErrorCode.CONTEXT_OVERFLOW,
                    "上下文段注入超出有效预算: " + tokens + " > " + maxContextTokens);
        }
        List<Map<String, Object>> out = new ArrayList<>(base.size() + 2);
        if (!headText.isBlank()) {
            out.add(Map.of("role", "system", "content", headText));
        }
        out.addAll(base);
        if (!tailText.isBlank()) {
            out.add(Map.of("role", "system", "content", tailText));
        }
        return out;
    }

    /** D 区按 Turn 冻结：同一 turnId 只渲染一次，后续步骤直接复用。 */
    private String frozenTail(String turnId, ContextRenderContext ctx) {
        synchronized (frozenTail) {
            String cached = frozenTail.get(turnId);
            if (cached != null) {
                return cached;
            }
            boolean[] external = {false};
            StringBuilder tail = new StringBuilder();
            for (ContextSegmentProvider p : segments.forZone(ContextZone.D_TURN_TAIL)) {
                tail.append(renderProvider(p, ctx, external));
            }
            // 不在此处静默截断：预算由 build 统一判定并抛 CONTEXT_OVERFLOW（不静默截断契约）。
            // 段提供者自身负责各自的字符上限（KnowledgeAutoInjectSegment 限 9000 字符）。
            String value = tail.toString();
            frozenTail.put(turnId, value);
            return value;
        }
    }

    /**
     * GRD-00：渲染单段。EXTERNAL 段做清理 + 每 Turn 随机边界包裹 + 来源标注，并置本 Turn 污点；
     * 其他段沿用 CTX-01 的 {@code [段名]} 形式。EXTERNAL 段渲染成功时把 {@code external[0]}
     * 置 true，供上层的平台边界说明与预算判定使用。
     */
    private String renderProvider(ContextSegmentProvider p, ContextRenderContext ctx, boolean[] external) {
        String text = safeRender(p, ctx);
        if (text.isBlank()) {
            return "";
        }
        if (p.trust() == null || !p.trust().requiresBoundary()) {
            return "[" + p.segmentName() + "]\n" + text + "\n";
        }
        external[0] = true;
        recordTaint(ctx.turnId(), p.segmentName(), p.sourceRef());
        return p.segmentName() + "\n"
                + boundary.wrap(ctx.turnId(), p.segmentName(), p.sourceRef(), text) + "\n";
    }

    /** GRD-00：把 EXTERNAL 段来源并入本 Turn 污点来源清单（幂等去重）。 */
    private void recordTaint(String turnId, String sourceType, String sourceRef) {
        String tag = sourceType + (sourceRef == null || sourceRef.isBlank() ? "" : "#" + sourceRef);
        String key = turnId == null ? "" : turnId;
        synchronized (taintSources) {
            List<String> existing = taintSources.get(key);
            if (existing == null) {
                List<String> created = new ArrayList<>();
                created.add(tag);
                taintSources.put(key, Collections.synchronizedList(created));
            } else if (!existing.contains(tag)) {
                existing.add(tag);
            }
        }
    }

    /** GRD-00：本 Turn 污点（{@code CLEAN} / {@code TAINTED}；只读探针）。 */
    public String turnTaint(String turnId) {
        List<String> sources = taintSources.get(turnId == null ? "" : turnId);
        return sources == null || sources.isEmpty() ? "CLEAN" : "TAINTED";
    }

    /** GRD-00：本 Turn 污点来源清单（{@code sourceType#sourceRef}；只读探针）。 */
    public List<String> turnTaintSources(String turnId) {
        List<String> sources = taintSources.get(turnId == null ? "" : turnId);
        return sources == null ? List.of() : List.copyOf(sources);
    }

    /**
     * GRD-00：首次回报本 Turn 污点时返回 true（供运行循环只写一次 {@code runtime_turn.taint}）。
     * 与是否 TAINTED 无关，调用方须先判 {@link #turnTaint(String)}。
     */
    public boolean markTaintReported(String turnId) {
        String key = turnId == null ? "" : turnId;
        synchronized (taintReported) {
            if (Boolean.TRUE.equals(taintReported.get(key))) {
                return false;
            }
            taintReported.put(key, Boolean.TRUE);
            return true;
        }
    }

    private static String safeRender(ContextSegmentProvider provider, ContextRenderContext ctx) {
        try {
            String text = provider.render(ctx);
            return text == null ? "" : text;
        } catch (BizException e) {
            if (e.getErrorCode().mustNotDegrade()) {
                // ADR-017：治理拒绝与 DEPENDENCY 兜底包装不得被「段渲染失败 → 空注入」掩盖。
                throw e;
            }
            log.warn("上下文段渲染失败 segment={}: {}", provider.segmentName(), e.toString());
            return "";
        } catch (RuntimeException e) {
            log.warn("上下文段渲染失败 segment={}: {}", provider.segmentName(), e.toString());
            return "";
        }
    }

    private static int estimateTokens(String text) {
        return text == null || text.isEmpty() ? 0 : text.length() / 3 + 1;
    }

    /**
     * 取本轮首条用户消息作为自动注入的检索问题（CTX-01）。
     *
     * <p>不能取「最后一条 user」：多步循环里的 steer / 插话也以 role=user 写入，
     * 会把检索问题污染成「继续」之类的短语。优先按 turnId 精确匹配本轮首条，
     * 无匹配时退回全会话首条 user（而不是最后一条）。
     */
    public static String queryForTurn(List<RuntimeItemDO> items, ObjectMapper mapper, String turnId) {
        RuntimeItemDO fallback = null;
        for (RuntimeItemDO item : items) {
            if (!ItemType.MESSAGE.wire().equals(item.getType())
                    || !"user".equals(item.getRole())
                    || Boolean.TRUE.equals(item.getDiscarded())) {
                continue;
            }
            if (turnId != null && !turnId.isBlank() && turnId.equals(item.getTurnId())) {
                return textOf(item, mapper);
            }
            if (fallback == null) {
                fallback = item;
            }
        }
        return fallback == null ? "" : textOf(fallback, mapper);
    }

    private static String textOf(RuntimeItemDO item, ObjectMapper mapper) {
        try {
            if (item.getContentJson() == null || item.getContentJson().isBlank()) {
                return "";
            }
            Map<String, Object> content = mapper.readValue(item.getContentJson(), new TypeReference<>() {});
            Object text = content.get("text");
            return text == null ? "" : String.valueOf(text);
        } catch (Exception e) {
            return "";
        }
    }

    public List<Map<String, Object>> build(List<RuntimeItemDO> items) {
        // 空注册表：不插入额外段，行为 = M2 现状
        if (!segments.isEmpty()) {
            // 占位：后续 RT-10 按 zone/order 调提供者；当前无实现 bean，不会进入
            segments.providers();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        List<RuntimeItemDO> pendingToolCalls = new ArrayList<>();
        Integer pendingStep = null;

        for (RuntimeItemDO item : items) {
            if (Boolean.TRUE.equals(item.getDiscarded())) {
                continue;
            }
            ItemType type;
            try {
                type = ItemType.fromWire(item.getType());
            } catch (IllegalArgumentException e) {
                continue;
            }
            switch (type) {
                case MESSAGE -> {
                    flushToolCalls(out, pendingToolCalls);
                    pendingToolCalls.clear();
                    pendingStep = null;
                    Map<String, Object> content = readMap(item.getContentJson());
                    Object text = content.getOrDefault("text", "");
                    out.add(Map.of("role", item.getRole() == null ? "user" : item.getRole(),
                            "content", String.valueOf(text)));
                }
                case TOOL_CALL -> {
                    if (pendingStep != null && !pendingStep.equals(item.getStep())) {
                        flushToolCalls(out, pendingToolCalls);
                        pendingToolCalls.clear();
                    }
                    pendingStep = item.getStep();
                    pendingToolCalls.add(item);
                }
                case TOOL_RESULT -> {
                    Map<String, Object> content = readMap(item.getContentJson());
                    // RT-09：pending 占位不进模型上下文，等 resolves 结果
                    if (content.containsKey("pending")) {
                        continue;
                    }
                    flushToolCalls(out, pendingToolCalls);
                    pendingToolCalls.clear();
                    pendingStep = null;
                    out.add(Map.of(
                            "role", "tool",
                            "tool_call_id", String.valueOf(content.getOrDefault("callId", "")),
                            "content", String.valueOf(content.getOrDefault("output", ""))));
                }
                default -> {
                    // reasoning / status / error 不进上下文
                }
            }
        }
        flushToolCalls(out, pendingToolCalls);
        return out;
    }

    private void flushToolCalls(List<Map<String, Object>> out, List<RuntimeItemDO> pending) {
        if (pending.isEmpty()) {
            return;
        }
        List<Map<String, Object>> calls = new ArrayList<>();
        for (RuntimeItemDO item : pending) {
            Map<String, Object> content = readMap(item.getContentJson());
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", content.getOrDefault("name", ""));
            Object args = content.get("arguments");
            fn.put("arguments", args instanceof String s ? s : writeJson(args == null ? Map.of() : args));
            calls.add(Map.of(
                    "id", String.valueOf(content.getOrDefault("callId", "")),
                    "type", "function",
                    "function", fn));
        }
        out.add(Map.of("role", "assistant", "tool_calls", calls));
    }

    private Map<String, Object> readMap(String json) {
        try {
            if (json == null || json.isBlank()) {
                return Map.of();
            }
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
