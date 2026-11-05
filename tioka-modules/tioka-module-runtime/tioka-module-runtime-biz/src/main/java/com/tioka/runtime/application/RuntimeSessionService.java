package com.tioka.runtime.application;

import com.tioka.framework.id.Ids;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.model.api.LlmClientApi;
import com.tioka.model.api.ModelRouterApi;
import com.tioka.runtime.api.RuntimeSessionApi;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;
import com.tioka.runtime.api.spi.WorkspaceFs;
import com.tioka.runtime.infrastructure.RuntimeSessionRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeIdemDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeSessionDO;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class RuntimeSessionService implements RuntimeSessionApi {
    private final ModelRouterApi modelRouter;
    private final LlmClientApi llm;
    private final ToolGateway toolGateway;
    private final WorkspaceFs workspaceFs;
    private final SkillInstallService skillInstall;
    private final ApplicationEventPublisher events;
    private final RuntimeSessionRepository sessions;
    private final ObjectMapper mapper;
    private final Map<String, SessionState> hot = new ConcurrentHashMap<>();

    public RuntimeSessionService(ModelRouterApi modelRouter,
                                 LlmClientApi llm,
                                 ToolGateway toolGateway,
                                 WorkspaceFs workspaceFs,
                                 SkillInstallService skillInstall,
                                 ApplicationEventPublisher events,
                                 RuntimeSessionRepository sessions, ObjectMapper mapper) {
        this.mapper = mapper;
        this.modelRouter = modelRouter;
        this.llm = llm;
        this.toolGateway = toolGateway;
        this.workspaceFs = workspaceFs;
        this.skillInstall = skillInstall;
        this.events = events;
        this.sessions = sessions;
    }

    @Override
    public SessionView create(CreateSessionRequest request) {
        String id = Ids.newId("sess");
        String workspaceId = workspaceIdOf(request.meta());
        List<SkillInstallService.SkillSnapshot> skills = toSnapshots(request.skills());
        SessionState s = new SessionState(id, "RUNNING", request.agentVersionId(), request.routingPlanId(),
                workspaceId, new CopyOnWriteArrayList<>(), false, null, skills, false);
        workspaceFs.ensureLayout(workspaceId, id);
        if (!skills.isEmpty()) {
            skillInstall.install(workspaceId, id, skills);
            s.skillsInstalled = true;
        }
        hot.put(id, s);
        save(s);
        return new SessionView(id, s.status, s.agentVersionId, s.routingPlanId);
    }

    private static List<SkillInstallService.SkillSnapshot> toSnapshots(List<SkillInstallInput> inputs) {
        if (inputs == null || inputs.isEmpty()) return List.of();
        return inputs.stream()
                .map(i -> new SkillInstallService.SkillSnapshot(
                        i.skillId(), i.name(), i.mode(), i.description(), i.skillMd()))
                .toList();
    }

    @Override
    public synchronized TurnView append(String sessionId, AppendRequest request) {
        SessionState s = require(sessionId);
        if (s.cancelled) {
            throw new BizException(ErrorCode.CONFLICT, "session cancelled");
        }
        if (request.clientMsgId() != null) {
            String existing = sessions.findTurnId(sessionId, request.clientMsgId());
            if (existing != null) {
                return getTurn(s, existing);
            }
        }
        String turnId = Ids.newId("turn");
        if (request.clientMsgId() != null) {
            RuntimeIdemDO idem = new RuntimeIdemDO();
            idem.setId(sessionId + ":" + request.clientMsgId());
            idem.setSessionId(sessionId);
            idem.setClientMsgId(request.clientMsgId());
            idem.setTurnId(turnId);
            sessions.insertIdem(idem);
        }
        List<PartView> parts = new ArrayList<>();
        parts.add(part("text", request.text(), Map.of("role", "user", "turnId", turnId)));
        publishPart(sessionId, turnId, parts.get(0));

        try {
            ensureSkillsInstalled(s);
            // RT-10：同 turn 只选模一次，工具续写复用 sel（不再调用 select）
            final ModelRouterApi.ModelSelection sel =
                    modelRouter.select(sessionId, s.routingPlanId, request.text());
            List<Map<String, Object>> messages = new ArrayList<>();
            if (s.steerText != null && !s.steerText.isBlank()) {
                messages.add(Map.of("role", "system", "content", "Steer: " + s.steerText));
            }
            for (String frag : skillInstall.promptFragments(s.skills)) {
                messages.add(Map.of("role", "system", "content", frag));
            }
            messages.add(Map.of("role", "user", "content", request.text()));
            ToolGateway.SessionToolContext toolCtx =
                    new ToolGateway.SessionToolContext(sessionId, s.agentVersionId, s.workspaceId, null);
            List<Map<String, Object>> tools = toolGateway.asOpenAiTools(toolCtx);

            LlmClientApi.CompletionResult result = streamAssistant(
                    sessionId, turnId, parts, s, sel, messages, tools);

            if (result.toolCalls() != null) {
                for (LlmClientApi.ToolCall tc : result.toolCalls()) {
                    if (s.cancelled) break;
                    PartView callPart = part("tool_call", tc.name(),
                            Map.of("id", tc.id(), "arguments", tc.argumentsJson(), "turnId", turnId));
                    parts.add(callPart);
                    publishPart(sessionId, turnId, callPart);

                    Map<String, Object> args = mapper.readValue(tc.argumentsJson(), new TypeReference<>() {});
                    ToolProvider.ToolResult tr = toolGateway.call(toolCtx, tc.name(), args);
                    if (s.cancelled) break;
                    PartView resultPart = part("tool_result", tr.content(),
                            Map.of("ok", tr.ok(), "tool", tc.name(), "turnId", turnId));
                    parts.add(resultPart);
                    publishPart(sessionId, turnId, resultPart);

                    messages.add(Map.of("role", "assistant", "content", "", "tool_calls", List.of(
                            Map.of("id", tc.id(), "type", "function", "function",
                                    Map.of("name", tc.name(), "arguments", tc.argumentsJson()))
                    )));
                    messages.add(Map.of("role", "tool", "tool_call_id", tc.id(), "content", tr.content()));
                    result = streamAssistant(sessionId, turnId, parts, s, sel, messages, List.of());
                }
            }

            if (s.cancelled) {
                s.turnParts.put(turnId, List.copyOf(parts));
                return new TurnView(turnId, "CANCELLED", parts);
            }
            s.turnParts.put(turnId, List.copyOf(parts));
            s.parts.addAll(parts);
            save(s);
            events.publishEvent(new UsageReportedEvent(sessionId, turnId, result.promptTokens(),
                    result.completionTokens(), sel.modelRef(), sel.reason()));
            return new TurnView(turnId, "COMPLETED", parts);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            PartView err = part("error", e.getMessage(), Map.of("turnId", turnId));
            parts.add(err);
            publishPart(sessionId, turnId, err);
            s.turnParts.put(turnId, List.copyOf(parts));
            return new TurnView(turnId, "FAILED", parts);
        }
    }

    @Override
    public void cancel(String sessionId) {
        SessionState s = require(sessionId);
        s.cancelled = true;
        s.status = "CANCELLED";
        save(s);
    }

    @Override
    public void steer(String sessionId, String text) {
        SessionState s = require(sessionId);
        s.steerText = text;
        save(s);
    }

    @Override
    public SessionView get(String sessionId) {
        SessionState s = require(sessionId);
        return new SessionView(s.id, s.status, s.agentVersionId, s.routingPlanId);
    }

    /** 供 Chat/测试注入已展开 Skill；未调用则 skills 为空。 */
    public void bindSkills(String sessionId, List<SkillInstallService.SkillSnapshot> skills) {
        SessionState s = require(sessionId);
        s.skills = skills == null ? List.of() : List.copyOf(skills);
        s.skillsInstalled = false;
        save(s);
    }

    public String workspaceId(String sessionId) {
        return require(sessionId).workspaceId;
    }

    public WorkspaceFs workspaceFs() {
        return workspaceFs;
    }

    private void ensureSkillsInstalled(SessionState s) {
        if (s.skillsInstalled || s.skills == null || s.skills.isEmpty()) return;
        skillInstall.install(s.workspaceId, s.id, s.skills);
        s.skillsInstalled = true;
    }

    private static String workspaceIdOf(Map<String, Object> meta) {
        if (meta == null) return "ws_default";
        Object v = meta.get("workspaceId");
        if (v == null || String.valueOf(v).isBlank()) return "ws_default";
        return String.valueOf(v);
    }

    private TurnView getTurn(SessionState s, String turnId) {
        List<PartView> cached = s.turnParts.get(turnId);
        if (cached != null) {
            return new TurnView(turnId, "COMPLETED", List.copyOf(cached));
        }
        List<PartView> filtered = s.parts.stream()
                .filter(p -> turnId.equals(payloadTurnId(p)))
                .toList();
        return new TurnView(turnId, "COMPLETED", filtered);
    }

    private static String payloadTurnId(PartView p) {
        if (p.payload() == null) return "";
        Object v = p.payload().get("turnId");
        return v == null ? "" : String.valueOf(v);
    }

    private SessionState require(String id) {
        SessionState cached = hot.get(id);
        if (cached != null) return cached;
        RuntimeSessionDO row = sessions.find(id);
        if (row == null) throw new BizException(ErrorCode.NOT_FOUND, "session 不存在");
        SessionState s = new SessionState(
                row.getId(), row.getStatus(), row.getAgentVersionId(), row.getRoutingPlanId(),
                row.getWorkspaceId() == null ? "ws_default" : row.getWorkspaceId(),
                new CopyOnWriteArrayList<>(readParts(row.getPartsJson())),
                Boolean.TRUE.equals(row.getCancelled()), row.getSteerText(),
                List.of(), false);
        hot.put(id, s);
        return s;
    }

    private void save(SessionState s) {
        RuntimeSessionDO row = new RuntimeSessionDO();
        row.setId(s.id);
        row.setStatus(s.status);
        row.setAgentVersionId(s.agentVersionId);
        row.setRoutingPlanId(s.routingPlanId);
        row.setWorkspaceId(s.workspaceId);
        row.setCancelled(s.cancelled);
        row.setSteerText(s.steerText);
        row.setPartsJson(writeParts(s.parts));
        sessions.upsert(row);
    }

    private List<PartView> readParts(String json) {
        try {
            if (json == null || json.isBlank()) return List.of();
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeParts(List<PartView> parts) {
        try {
            return mapper.writeValueAsString(parts == null ? List.of() : parts);
        } catch (Exception e) {
            return "[]";
        }
    }

    private PartView part(String type, String text, Map<String, Object> payload) {
        return new PartView(Ids.newId("part"), type, text, payload);
    }

    private void publishPart(String sessionId, String turnId, PartView part) {
        events.publishEvent(new PartAppendedEvent(sessionId, turnId, part));
    }

    private LlmClientApi.CompletionResult streamAssistant(String sessionId,
                                                          String turnId,
                                                          List<PartView> parts,
                                                          SessionState s,
                                                          ModelRouterApi.ModelSelection sel,
                                                          List<Map<String, Object>> messages,
                                                          List<Map<String, Object>> tools) throws Exception {
        StringBuilder acc = new StringBuilder();
        LlmClientApi.CompletionResult result = llm.streamComplete(
                sel.baseUrl(), sel.apiKeyRef(), sel.modelName(), messages, tools,
                delta -> {
                    if (s.cancelled) return;
                    String piece = delta.textDelta();
                    if (piece == null || piece.isEmpty()) return;
                    acc.append(piece);
                    publishPart(sessionId, turnId,
                            part("text", piece, Map.of("role", "assistant", "delta", true, "turnId", turnId)));
                });
        if (s.cancelled) {
            return result;
        }
        boolean hasTools = result.toolCalls() != null && !result.toolCalls().isEmpty();
        String finalText = result.content() != null && !result.content().isBlank()
                ? result.content()
                : acc.toString();
        if (!hasTools && finalText != null && !finalText.isBlank()) {
            PartView finalPart = part("text", finalText,
                    Map.of("role", "assistant", "delta", false, "turnId", turnId));
            parts.add(finalPart);
            publishPart(sessionId, turnId, finalPart);
        }
        return result;
    }

    static final class SessionState {
        final String id;
        String status;
        final String agentVersionId;
        final String routingPlanId;
        final String workspaceId;
        final List<PartView> parts;
        final Map<String, List<PartView>> turnParts = new ConcurrentHashMap<>();
        volatile boolean cancelled;
        volatile String steerText;
        List<SkillInstallService.SkillSnapshot> skills;
        boolean skillsInstalled;

        SessionState(String id, String status, String agentVersionId, String routingPlanId, String workspaceId,
                     List<PartView> parts, boolean cancelled, String steerText,
                     List<SkillInstallService.SkillSnapshot> skills, boolean skillsInstalled) {
            this.id = id;
            this.status = status;
            this.agentVersionId = agentVersionId;
            this.routingPlanId = routingPlanId;
            this.workspaceId = workspaceId;
            this.parts = parts;
            this.cancelled = cancelled;
            this.steerText = steerText;
            this.skills = skills == null ? List.of() : skills;
            this.skillsInstalled = skillsInstalled;
        }
    }

    public record PartAppendedEvent(String sessionId, String turnId, PartView part) {}
    public record UsageReportedEvent(String sessionId, String turnId, int promptTokens, int completionTokens,
                                     String modelRef, String routeReason) {
        public UsageReportedEvent(String sessionId, String turnId, int promptTokens, int completionTokens, String modelRef) {
            this(sessionId, turnId, promptTokens, completionTokens, modelRef, null);
        }
    }
}
