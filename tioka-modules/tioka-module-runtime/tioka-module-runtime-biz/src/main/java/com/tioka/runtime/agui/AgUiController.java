package com.tioka.runtime.agui;

import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import com.tioka.runtime.agui.dto.AgUiRunAgentInput;
import com.tioka.system.api.audit.Audited;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AG-UI 兼容网关（M2-04 HCI-06）：{@code POST /api/agui/agents/{agentVersionId}/run}。
 */
@RestController
@RequestMapping("/api/agui")
@RequirePermission(Permissions.CHAT_USE)
public class AgUiController {
    private final AgUiRunService runs;

    public AgUiController(AgUiRunService runs) {
        this.runs = runs;
    }

    @PostMapping(path = "/agents/{agentVersionId}/run",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Audited(action = "agui.agent.run", resourceType = "agent", resourceIdArg = "#agentVersionId")
    public SseEmitter run(@PathVariable String agentVersionId,
                          @RequestBody AgUiRunAgentInput input) {
        return runs.run(agentVersionId, input);
    }
}
