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
                command.runId()));

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
