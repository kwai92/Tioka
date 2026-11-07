package com.tioka.orchestration.application.dispatch;

import com.tioka.orchestration.api.dispatch.OrchActionDispatcher;
import com.tioka.orchestration.api.dispatch.OrchActionType;
import com.tioka.orchestration.api.dispatch.OrchDispatchCommand;
import org.springframework.stereotype.Component;

/**
 * admission 在决定阶段处理；派工侧若仍落到本类型，委托 agent 只读研判。
 * （完整 admission_skip 回执路径由 PK-13 衔接。）
 */
@Component
public class AdmissionActionDispatcher implements OrchActionDispatcher {

    private final AgentActionDispatcher agent;

    public AdmissionActionDispatcher(AgentActionDispatcher agent) {
        this.agent = agent;
    }

    @Override
    public OrchActionType type() {
        return OrchActionType.ADMISSION;
    }

    @Override
    public void dispatch(OrchDispatchCommand command) {
        agent.dispatch(new OrchDispatchCommand(
                command.runId(),
                command.workItemId(),
                command.ruleVersionId(),
                OrchActionType.AGENT,
                command.action(),
                command.eventAttrs(),
                command.eventBody()));
    }
}
