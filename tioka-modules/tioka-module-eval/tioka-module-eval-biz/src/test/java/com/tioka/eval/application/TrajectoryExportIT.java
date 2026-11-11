package com.tioka.eval.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.eval.api.TrajectoryExportApi;
import com.tioka.framework.error.BizException;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.TrajectoryApi;
import com.tioka.runtime.api.kernel.TurnStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EVO-01 轨迹导出验收（M3-06 §4.1）：
 * {@code #labelRestricted} CONFIDENTIAL 以上需空间管理员权限（DAT-02 数据标签机制）；
 * 导出 JSON 按 OTel GenAI 语义约定命名 span 属性；按 Turn / 时间范围两种模式可用。
 */
class TrajectoryExportIT {

    private static final String THREAD = "thr_c13";
    private static final Instant T0 = Instant.parse("2026-11-11T08:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-11T09:00:00Z");

    /** 内存 TrajectoryApi 替身（同 HCI-07b 查询契约）。 */
    private static final class InMemoryTrajectoryApi implements TrajectoryApi {
        final Map<String, Trajectory> byTurn = new java.util.LinkedHashMap<>();
        final Map<String, List<TurnSummary>> byThread = new java.util.LinkedHashMap<>();

        @Override
        public Trajectory turn(String turnId) {
            Trajectory t = byTurn.get(turnId);
            if (t == null) {
                throw new BizException(com.tioka.framework.error.ErrorCode.NOT_FOUND, "turn 不存在");
            }
            return t;
        }

        @Override
        public List<TurnSummary> turns(String threadId, int page, int size) {
            List<TurnSummary> all = byThread.getOrDefault(threadId, List.of());
            int from = Math.min((page - 1) * size, all.size());
            return all.subList(from, Math.min(from + size, all.size()));
        }
    }

    private final InMemoryTrajectoryApi trajectories = new InMemoryTrajectoryApi();
    private final Map<String, String> threadLabels = new java.util.LinkedHashMap<>();

    private PrincipalHolder holderFor(Principal p) {
        return new PrincipalHolder() {
            @Override public Principal current() { return p; }
            @Override public Optional<Principal> find() { return Optional.ofNullable(p); }
            @Override public <T> T runAs(Principal principal, java.util.function.Supplier<T> body) {
                return body.get();
            }
        };
    }

    private TrajectoryExportService serviceFor(Principal p) {
        return new TrajectoryExportService(trajectories,
                threadId -> Optional.ofNullable(threadLabels.get(threadId)),
                holderFor(p), new ObjectMapper().findAndRegisterModules());
    }

    private void seed(String threadId, String label) {
        seed(threadId, label, "turn_a");
    }

    private void seed(String threadId, String label, String turnId) {
        threadLabels.put(threadId, label);
        RuntimeApi.ItemView message = new RuntimeApi.ItemView("itm_1", threadId, turnId, 1L, 0,
                ItemType.MESSAGE, "assistant", Map.of("text", "已为您更新订单"), T0);
        RuntimeApi.ItemView toolCall = new RuntimeApi.ItemView("itm_2", threadId, turnId, 2L, 1,
                ItemType.TOOL_CALL, "assistant", Map.of("toolName", "order.update"), T0);
        RuntimeApi.ItemView toolResult = new RuntimeApi.ItemView("itm_3", threadId, turnId, 3L, 1,
                ItemType.TOOL_RESULT, "tool", Map.of("ok", true), T0);
        TrajectoryApi.Trajectory a = new TrajectoryApi.Trajectory(
                new RuntimeApi.TurnView(turnId, threadId, 1, TurnStatus.COMPLETED, "chat", null,
                        2, null, null, 100, 50, T0, T0, T1),
                List.of(
                        new TrajectoryApi.Step(0, List.of(message), "model-x", 100, 50, 1200L, 3000L, false),
                        new TrajectoryApi.Step(1, List.of(toolCall, toolResult), "model-x",
                                null, null, 300L, 0L, false)),
                "http://trace/local");
        trajectories.byTurn.put(turnId, a);
        trajectories.byThread.put(threadId, List.of(
                new TrajectoryApi.TurnSummary(turnId, 1, TurnStatus.COMPLETED, "chat", 2,
                        100, 50, 3000L, T0, T1)));
    }

    private static Principal principalWith(Set<String> permissions) {
        return new Principal("1", "ws_c13", PrincipalType.USER, Set.of("builder"), permissions, "tester");
    }

    @Test
    void labelRestricted() {
        seed(THREAD, "CONFIDENTIAL");
        TrajectoryExportService normal = serviceFor(principalWith(Set.of(Permissions.CHAT_USE)));
        TrajectoryExportService admin = serviceFor(principalWith(Set.of(
                Permissions.CHAT_USE, Permissions.WORKSPACE_MANAGE)));

        // CONFIDENTIAL：普通用户拒绝，空间管理员放行
        BizException denied = assertThrows(BizException.class, () -> normal.export(
                new TrajectoryExportApi.ExportRequest(THREAD, null, null, null)));
        assertEquals(com.tioka.framework.error.ErrorCode.FORBIDDEN, denied.getErrorCode());

        String json = admin.export(new TrajectoryExportApi.ExportRequest(THREAD, null, null, null));
        assertTrue(json.contains("tioka.trajectory.export/v1"));
        assertTrue(json.contains("gen_ai.request.model"), "OTel GenAI 语义约定 span 属性");
        assertTrue(json.contains("gen_ai.usage.input_tokens"));
        assertTrue(json.contains("gen_ai.usage.output_tokens"));
        assertTrue(json.contains("gen_ai.conversation.id"));
        assertTrue(json.contains("gen_ai.tool.name"), "工具步骤映射 execute_tool span");
        assertFalse(json.contains("gen_ai.prompt"), "不导出原始 prompt 正文（最小化）");

        // INTERNAL：普通用户可导出
        seed(THREAD, "INTERNAL");
        String visible = serviceFor(principalWith(Set.of(Permissions.CHAT_USE)))
                .export(new TrajectoryExportApi.ExportRequest(THREAD, null, null, null));
        assertTrue(visible.contains("turn_a"));
    }

    @Test
    void exportByTurnAndTimeRange() {
        seed(THREAD, "INTERNAL");
        TrajectoryExportService svc = serviceFor(principalWith(Set.of(Permissions.CHAT_USE)));

        // 按 Turn 导出（threadId 反查标签）
        String byTurn = svc.export(new TrajectoryExportApi.ExportRequest(null, List.of("turn_a"), null, null));
        assertTrue(byTurn.contains("turn_a"));
        assertTrue(byTurn.contains("\"dataLabel\":\"INTERNAL\""));

        // 时间范围：起点晚于 Turn → 空导出
        String later = svc.export(new TrajectoryExportApi.ExportRequest(THREAD, null,
                T1.plusSeconds(1), null));
        assertTrue(later.contains("\"turns\":[]"));

        // 时间范围命中
        String hit = svc.export(new TrajectoryExportApi.ExportRequest(THREAD, null, T0, T1));
        assertTrue(hit.contains("turn_a"));
    }

    @Test
    void crossThreadExportRejectsConfidential() {
        seed(THREAD, "INTERNAL");
        seed("thr_c13_sec", "CONFIDENTIAL", "turn_b");
        TrajectoryExportService normal = serviceFor(principalWith(Set.of(Permissions.CHAT_USE)));
        TrajectoryExportService admin = serviceFor(principalWith(Set.of(
                Permissions.CHAT_USE, Permissions.WORKSPACE_MANAGE)));

        // 跨 thread turnIds 导出：首个 thread 是 INTERNAL、CONFIDENTIAL 内容混入其中 → 必须逐 thread 判定并拒绝
        BizException denied = assertThrows(BizException.class, () -> normal.export(
                new TrajectoryExportApi.ExportRequest(null, List.of("turn_a", "turn_b"), null, null)));
        assertEquals(com.tioka.framework.error.ErrorCode.FORBIDDEN, denied.getErrorCode());

        // 反序（首个即 CONFIDENTIAL）同样拒绝
        BizException denied2 = assertThrows(BizException.class, () -> normal.export(
                new TrajectoryExportApi.ExportRequest(null, List.of("turn_b", "turn_a"), null, null)));
        assertEquals(com.tioka.framework.error.ErrorCode.FORBIDDEN, denied2.getErrorCode());

        // 空间管理员跨 thread 导出放行
        String json = admin.export(new TrajectoryExportApi.ExportRequest(null, List.of("turn_a", "turn_b"), null, null));
        assertTrue(json.contains("turn_a"));
        assertTrue(json.contains("turn_b"));
        assertTrue(json.contains("thr_c13_sec"));
    }
}
