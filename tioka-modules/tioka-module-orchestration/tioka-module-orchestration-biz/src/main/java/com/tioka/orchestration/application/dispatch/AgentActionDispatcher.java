package com.tioka.orchestration.application.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.orchestration.api.dispatch.OrchActionDispatcher;
import com.tioka.orchestration.api.dispatch.OrchActionType;
import com.tioka.orchestration.api.dispatch.OrchDispatchCommand;
import com.tioka.orchestration.infrastructure.OrchEngineRepository;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import com.tioka.orchestration.infrastructure.persistence.OrchWorkItemDO;
import com.tioka.runtime.api.kernel.RuntimeApi;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * agent 动作派工：createThread / startTurn（约束 F23）。
 */
@Component
public class AgentActionDispatcher implements OrchActionDispatcher {

    private final RuntimeApi runtime;
    private final OrchEngineRepository repo;
    private final ObjectMapper mapper;

    public AgentActionDispatcher(RuntimeApi runtime, OrchEngineRepository repo, ObjectMapper mapper) {
        this.runtime = runtime;
        this.repo = repo;
        this.mapper = mapper;
    }

    @Override
    public OrchActionType type() {
        return OrchActionType.AGENT;
    }

    @Override
    public void dispatch(OrchDispatchCommand command) {
        String agentVersionId = stringVal(command.action().get("agentVersionId"));
        if (agentVersionId == null || agentVersionId.isBlank()) {
            throw new IllegalStateException("agentVersionId 缺失");
        }
        OrchWorkItemDO wi = repo.findWorkItem(command.workItemId());
        String threadId = wi == null ? null : wi.getThreadId();
        if (threadId == null || threadId.isBlank()) {
            RuntimeApi.ThreadView thread = runtime.createThread(new RuntimeApi.CreateThreadCmd(
                    agentVersionId,
                    "orch",
                    command.runId(),
                    "orch:" + command.workItemId(),
                    Map.of(
                            "workItemId", command.workItemId(),
                            "ruleVersionId", command.ruleVersionId(),
                            "runId", command.runId())));
            threadId = thread.threadId();
            if (wi != null) {
                wi.setThreadId(threadId);
                wi.setUpdateAt(java.time.LocalDateTime.now());
                repo.updateWorkItem(wi);
            }
        }

        String prompt = renderPrompt(command);
        Integer maxSteps = intVal(command.action().get("maxSteps"));
        List<String> allow = new ArrayList<>();
        allow.add("orch.receipt");
        Object allowObj = command.action().get("toolAllowList");
        if (allowObj instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    allow.add(String.valueOf(o));
                }
            }
        }

        RuntimeApi.TurnHandle handle = runtime.startTurn(threadId, new RuntimeApi.StartTurnCmd(
                command.runId() + ":1",
                List.of(RuntimeApi.InputItem.text(prompt)),
                new RuntimeApi.TurnOptions(maxSteps, null, allow),
                "orch",
                command.runId(),
                onBehalfOf(command.workItemId()),
                mandateScope(command.workItemId())));

        OrchRunDO run = repo.findRun(command.runId());
        if (run != null) {
            run.setThreadId(threadId);
            run.setCurrentTurnId(handle.turnId());
            run.setUpdateAt(java.time.LocalDateTime.now());
            repo.updateRun(run);
        }
    }

    private String renderPrompt(OrchDispatchCommand command) {
        Object raw = command.action().get("prompt");
        String template = raw == null ? "处理以下事件：{{event.body}}" : String.valueOf(raw);
        String body;
        try {
            body = mapper.writeValueAsString(command.eventBody());
        } catch (Exception e) {
            body = String.valueOf(command.eventBody());
        }
        return template.replace("{{event.body}}", body);
    }

    /**
     * MAS-01（ADR-039）：orch_mandate.requester_user_id → StartTurnCmd.onBehalfOf（代表经办人）。
     */
    private String onBehalfOf(String workItemId) {
        try {
            var mandate = repo.findLatestMandate(workItemId);
            return mandate == null ? null : mandate.getRequesterUserId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** MAS-01：orch_mandate.scope_json → StartTurnCmd.mandateScope（根授予 scope）。 */
    private java.util.Map<String, java.util.List<String>> mandateScope(String workItemId) {
        try {
            var mandate = repo.findLatestMandate(workItemId);
            if (mandate == null || mandate.getScopeJson() == null || mandate.getScopeJson().isBlank()) {
                return java.util.Map.of();
            }
            var parsed = mapper.readValue(mandate.getScopeJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, java.util.List<String>>>() {});
            return parsed == null ? java.util.Map.of() : parsed;
        } catch (RuntimeException | java.io.IOException e) {
            return java.util.Map.of();
        }
    }

    private static String stringVal(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Integer intVal(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
