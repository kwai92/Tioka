package com.tioka.eval.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.eval.api.TrajectoryExportApi;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.label.DataLabel;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.ThreadLabelApi;
import com.tioka.runtime.api.kernel.TrajectoryApi;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * EVO-01：轨迹导出（M3-06 §4.1）。复用 HCI-07b {@link TrajectoryApi} 查询；
 * 导出 JSON 按 OTel GenAI 语义约定命名 span 属性（ADR-029 第 67 条：导出层映射，
 * 不把属性名写进核心表）。导出受数据标签限制（DAT-02 / V164 runtime_thread.data_label）：
 * CONFIDENTIAL 以上需空间管理员权限（workspace:manage）。类级条件见
 * {@code tioka.eval.enabled}：server 全局 scanBasePackages 会拉起注解 bean（C-8 教训）。
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "tioka.eval", name = "enabled", havingValue = "true")
public class TrajectoryExportService implements TrajectoryExportApi {

    static final String EXPORT_VERSION = "tioka.trajectory.export/v1";

    private final TrajectoryApi trajectories;
    private final ThreadLabelApi threadLabels;
    private final PrincipalHolder principals;
    private final ObjectMapper mapper;

    public TrajectoryExportService(TrajectoryApi trajectories,
                                   ThreadLabelApi threadLabels,
                                   PrincipalHolder principals,
                                   ObjectMapper mapper) {
        this.trajectories = trajectories;
        this.threadLabels = threadLabels;
        this.principals = principals;
        this.mapper = mapper;
    }

    @Override
    public String export(ExportRequest request) {
        if (request == null || (request.turnIds() == null || request.turnIds().isEmpty())
                && (request.threadId() == null || request.threadId().isBlank())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "导出须指定 turnIds 或 threadId");
        }

        List<TrajectoryApi.Trajectory> trajectoriesOut = new ArrayList<>();
        String threadId = request.threadId();
        if (request.turnIds() != null && !request.turnIds().isEmpty()) {
            for (String turnId : request.turnIds()) {
                TrajectoryApi.Trajectory t = trajectories.turn(turnId);
                trajectoriesOut.add(t);
                if (threadId == null || threadId.isBlank()) {
                    threadId = t.turn().threadId();
                }
            }
        } else {
            collectTurnsByRange(request, trajectoriesOut);
        }

        // DAT-02 / M3-06 §4.1：对导出涉及的每个 thread 逐个判定数据标签。turnIds 可以
        // 混入多个 thread 的内容，仅按首个 thread 校验会被绕过（fail-closed：一律拒绝，
        // 与单 thread 路径既有语义一致）；时间范围空导出时仍校验请求的 threadId。
        Set<String> involvedThreads = new LinkedHashSet<>();
        if (threadId != null && !threadId.isBlank()) {
            involvedThreads.add(threadId);
        }
        for (TrajectoryApi.Trajectory t : trajectoriesOut) {
            involvedThreads.add(t.turn().threadId());
        }
        for (String involved : involvedThreads) {
            requireLabelClearance(involved);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exportVersion", EXPORT_VERSION);
        out.put("threadId", threadId);
        out.put("dataLabel", threadLabels.dataLabel(threadId).orElse(null));
        out.put("exportedAt", Instant.now().toString());
        out.put("turns", trajectoriesOut.stream().map(this::toExportTurn).toList());
        try {
            return mapper.writeValueAsString(out);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "导出序列化失败: " + e.getMessage());
        }
    }

    private void collectTurnsByRange(ExportRequest request, List<TrajectoryApi.Trajectory> out) {
        int page = 1;
        while (true) {
            List<TrajectoryApi.TurnSummary> summaries = trajectories.turns(request.threadId(), page, 100);
            if (summaries.isEmpty()) {
                return;
            }
            for (TrajectoryApi.TurnSummary s : summaries) {
                if (inRange(s.startedAt(), request.from(), request.to())) {
                    out.add(trajectories.turn(s.turnId()));
                }
            }
            if (summaries.size() < 100) {
                return;
            }
            page++;
        }
    }

    private static boolean inRange(Instant startedAt, Instant from, Instant to) {
        if (startedAt == null) {
            return from == null && to == null;
        }
        if (from != null && startedAt.isBefore(from)) {
            return false;
        }
        return to == null || !startedAt.isAfter(to);
    }

    /** DAT-02：CONFIDENTIAL 以上需空间管理员权限。 */
    private void requireLabelClearance(String threadId) {
        DataLabel label = parseLabel(threadLabels.dataLabel(threadId).orElse(null), DataLabel.INTERNAL);
        if (label.ordinal() < DataLabel.CONFIDENTIAL.ordinal()) {
            return;
        }
        Principal p = principals.current();
        if (p != null && (p.hasPermission(Permissions.WORKSPACE_MANAGE)
                || p.hasPermission(Permissions.ALL)
                || p.hasPermission(Permissions.TENANT_ADMIN))) {
            return;
        }
        throw new BizException(ErrorCode.FORBIDDEN,
                "数据标签 " + label + " 的轨迹导出需空间管理员权限");
    }

    static DataLabel parseLabel(String raw, DataLabel fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return DataLabel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------
    // OTel GenAI 语义约定映射（导出层；不写核心表）
    // ------------------------------------------------------------------

    private Map<String, Object> toExportTurn(TrajectoryApi.Trajectory t) {
        Map<String, Object> turn = new LinkedHashMap<>();
        turn.put("turnId", t.turn().turnId());
        turn.put("threadId", t.turn().threadId());
        turn.put("seq", t.turn().seq());
        turn.put("status", String.valueOf(t.turn().status()));
        turn.put("origin", t.turn().origin());
        turn.put("startedAt", t.turn().startedAt() == null ? null : t.turn().startedAt().toString());
        turn.put("endedAt", t.turn().endedAt() == null ? null : t.turn().endedAt().toString());
        turn.put("traceUrl", t.traceUrl());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (TrajectoryApi.Step s : t.steps()) {
            steps.add(toExportStep(t, s));
        }
        turn.put("steps", steps);
        return turn;
    }

    private Map<String, Object> toExportStep(TrajectoryApi.Trajectory t, TrajectoryApi.Step s) {
        String operation = stepOperation(s);
        String modelRef = s.modelRef();
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("gen_ai.system", "tioka");
        attrs.put("gen_ai.conversation.id", t.turn().threadId());
        attrs.put("gen_ai.operation.name", operation);
        if (modelRef != null && !modelRef.isBlank()) {
            attrs.put("gen_ai.request.model", modelRef);
        }
        if (s.promptTokens() != null) {
            attrs.put("gen_ai.usage.input_tokens", s.promptTokens());
        }
        if (s.completionTokens() != null) {
            attrs.put("gen_ai.usage.output_tokens", s.completionTokens());
        }
        if (s.latencyMs() != null) {
            attrs.put("gen_ai.usage.latency_ms", s.latencyMs());
        }
        String toolName = firstToolName(s);
        if (toolName != null) {
            attrs.put("gen_ai.tool.name", toolName);
            attrs.put("gen_ai.tool.call.id", t.turn().turnId() + ":" + s.step());
        }
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("stepNumber", s.step());
        step.put("spanName", toolName != null ? "execute_tool " + toolName
                : "chat " + (modelRef == null ? "" : modelRef));
        step.put("attributes", attrs);
        step.put("items", s.items());
        return step;
    }

    private static String stepOperation(TrajectoryApi.Step s) {
        if (s.items() == null) {
            return "chat";
        }
        boolean hasTool = s.items().stream().anyMatch(i ->
                i.type() == ItemType.TOOL_CALL || i.type() == ItemType.TOOL_RESULT);
        return hasTool ? "execute_tool" : "chat";
    }

    private static String firstToolName(TrajectoryApi.Step s) {
        if (s.items() == null) {
            return null;
        }
        return s.items().stream()
                .filter(i -> i.type() == ItemType.TOOL_CALL || i.type() == ItemType.TOOL_RESULT)
                .map(i -> {
                    if (i.content() == null) {
                        return null;
                    }
                    Object name = i.content().get("toolName") != null
                            ? i.content().get("toolName") : i.content().get("name");
                    return name == null ? null : String.valueOf(name);
                })
                .filter(n -> !n.isBlank())
                .findFirst()
                .orElse(null);
    }
}
