package com.tioka.runtime.kernel.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.runtime.api.RuntimeSessionApi;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.api.spi.WorkspaceFs;
import com.tioka.runtime.application.SkillInstallService;
import com.tioka.runtime.config.KernelProperties;
import com.tioka.runtime.infrastructure.RuntimeSessionRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeSessionDO;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeItemDO;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeTurnDO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * M0：旧 {@link RuntimeSessionApi} → 新 {@link RuntimeApi}（M1 CHT-02 后删除）。
 */
@Service
public class LegacySessionAdapter implements RuntimeSessionApi {
    private final RuntimeApi runtime;
    private final RuntimeSessionRepository sessions;
    private final TurnRepository turns;
    private final ItemRepository items;
    private final WorkspaceFs workspaceFs;
    private final SkillInstallService skillInstall;
    private final ObjectMapper mapper;
    private final Duration appendTimeout;

    public LegacySessionAdapter(RuntimeApi runtime,
                                RuntimeSessionRepository sessions,
                                TurnRepository turns,
                                ItemRepository items,
                                WorkspaceFs workspaceFs,
                                SkillInstallService skillInstall,
                                ObjectMapper mapper,
                                KernelProperties props) {
        this.runtime = runtime;
        this.sessions = sessions;
        this.turns = turns;
        this.items = items;
        this.workspaceFs = workspaceFs;
        this.skillInstall = skillInstall;
        this.mapper = mapper;
        Duration configured = props.getLegacyAppendTimeout();
        this.appendTimeout = configured == null ? Duration.ofSeconds(120) : configured;
    }

    @Override
    public SessionView create(CreateSessionRequest request) {
        String workspaceId = workspaceIdOf(request.meta());
        RuntimeApi.ThreadView thr = runtime.createThread(new RuntimeApi.CreateThreadCmd(
                request.agentVersionId(), "chat", null, null,
                request.meta() == null ? null : toStringMap(request.meta())));
        if (workspaceId != null && !workspaceId.isBlank()) {
            workspaceFs.ensureLayout(workspaceId, thr.threadId());
            if (request.skills() != null && !request.skills().isEmpty()) {
                skillInstall.install(workspaceId, thr.threadId(), request.skills().stream()
                        .map(s -> new SkillInstallService.SkillSnapshot(
                                s.skillId(), s.name(), s.mode(), s.description(), s.skillMd()))
                        .toList());
            }
        }
        RuntimeSessionDO row = sessions.find(thr.threadId());
        String plan = row == null ? request.routingPlanId() : row.getRoutingPlanId();
        return new SessionView(thr.threadId(), "RUNNING", request.agentVersionId(), plan);
    }

    @Override
    public TurnView append(String sessionId, AppendRequest request) {
        RuntimeApi.TurnHandle handle = runtime.startTurn(sessionId, new RuntimeApi.StartTurnCmd(
                request.clientMsgId(),
                List.of(RuntimeApi.InputItem.text(request.text())),
                null,
                "chat",
                null));
        RuntimeApi.TurnView done = awaitTerminal(handle.turnId());
        List<PartView> parts = new ArrayList<>();
        parts.add(new PartView(Ids.newId("part"), "text", request.text(),
                Map.of("role", "user", "turnId", handle.turnId())));
        for (RuntimeItemDO row : items.listByTurn(handle.turnId())) {
            parts.add(toPart(row));
        }
        return new TurnView(handle.turnId(), done.status().name(), parts);
    }

    @Override
    public void cancel(String sessionId) {
        turns.findActive(sessionId).ifPresent(active -> runtime.interrupt(active.getId()));
    }

    @Override
    public void steer(String sessionId, String text) {
        RuntimeTurnDO active = turns.findActive(sessionId)
                .orElseThrow(() -> new BizException(ErrorCode.CONFLICT, "无活动 Turn，无法插话"));
        runtime.steer(active.getId(), text);
    }

    @Override
    public SessionView get(String sessionId) {
        RuntimeSessionDO row = sessions.find(sessionId);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return new SessionView(row.getId(), row.getStatus(), row.getAgentVersionId(), row.getRoutingPlanId());
    }

    private RuntimeApi.TurnView awaitTerminal(String turnId) {
        Instant deadline = Instant.now().plus(appendTimeout);
        while (Instant.now().isBefore(deadline)) {
            RuntimeApi.TurnView v = runtime.getTurn(turnId);
            TurnStatus s = v.status();
            if (s == TurnStatus.COMPLETED || s == TurnStatus.FAILED || s == TurnStatus.CANCELLED) {
                return v;
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BizException(ErrorCode.INTERNAL, "等待 Turn 被中断");
            }
        }
        throw new BizException(ErrorCode.DEPENDENCY, "等待 Turn 超时");
    }

    private PartView toPart(RuntimeItemDO item) {
        ItemType itemType = ItemType.fromWire(item.getType());
        Map<String, Object> content = parseContent(item.getContentJson());
        String type = switch (itemType) {
            case MESSAGE -> "text";
            case TOOL_CALL -> "tool_call";
            case TOOL_RESULT -> "tool_result";
            default -> item.getType();
        };
        String text;
        if (itemType == ItemType.MESSAGE) {
            text = String.valueOf(content.getOrDefault("text", ""));
        } else if (itemType == ItemType.TOOL_CALL) {
            text = String.valueOf(content.getOrDefault("name", ""));
        } else if (itemType == ItemType.TOOL_RESULT) {
            text = String.valueOf(content.getOrDefault("output", ""));
        } else {
            text = "";
        }
        Map<String, Object> payload = new HashMap<>(content);
        payload.put("turnId", item.getTurnId());
        payload.put("delta", false);
        return new PartView(item.getId(), type, text, payload);
    }

    private Map<String, Object> parseContent(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String workspaceIdOf(Map<String, Object> meta) {
        if (meta == null) {
            return null;
        }
        Object v = meta.get("workspaceId");
        return v == null ? null : String.valueOf(v);
    }

    private static Map<String, String> toStringMap(Map<String, Object> meta) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, Object> e : meta.entrySet()) {
            out.put(e.getKey(), e.getValue() == null ? null : String.valueOf(e.getValue()));
        }
        return out;
    }
}
