package com.tioka.runtime.kernel.api;

import com.tioka.system.api.audit.Audited;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import com.tioka.runtime.api.kernel.RuntimeApi;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/runtime")
@RequirePermission(Permissions.CHAT_USE)
public class ThreadController {
    private final RuntimeApi runtime;

    public ThreadController(RuntimeApi runtime) {
        this.runtime = runtime;
    }

    @PostMapping("/threads")
    @Audited(action = "runtime.thread.create", resourceType = "thread")
    public ApiResponse<RuntimeApi.ThreadView> create(@RequestBody Map<String, Object> body) {
        String agentVersionId = str(body.get("agentVersionId"));
        String origin = str(body.get("origin"));
        String originRef = str(body.get("originRef"));
        String title = str(body.get("title"));
        @SuppressWarnings("unchecked")
        Map<String, String> metadata = body.get("metadata") instanceof Map<?, ?> m
                ? (Map<String, String>) m : Map.of();
        return ApiResponse.ok(runtime.createThread(
                new RuntimeApi.CreateThreadCmd(agentVersionId, origin, originRef, title, metadata)));
    }

    @PostMapping("/threads/{id}/turns")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Audited(action = "runtime.turn.start", resourceType = "thread", resourceIdArg = "#id")
    public ApiResponse<RuntimeApi.TurnHandle> startTurn(@PathVariable String id,
                                                        @RequestBody Map<String, Object> body) {
        String clientMsgId = str(body.get("clientMsgId"));
        List<RuntimeApi.InputItem> input = List.of();
        if (body.get("input") instanceof List<?> list) {
            input = list.stream()
                    .filter(Map.class::isInstance)
                    .map(x -> (Map<?, ?>) x)
                    .map(m -> new RuntimeApi.InputItem(
                            str(m.get("type")), str(m.get("text")), str(m.get("fileRef"))))
                    .toList();
        } else if (body.get("text") != null) {
            input = List.of(RuntimeApi.InputItem.text(str(body.get("text"))));
        }
        RuntimeApi.TurnOptions options = null;
        if (body.get("options") instanceof Map<?, ?> opt) {
            Integer maxSteps = opt.get("maxSteps") instanceof Number n ? n.intValue() : null;
            String approval = str(opt.get("approvalPolicy"));
            @SuppressWarnings("unchecked")
            List<String> allow = opt.get("toolAllowList") instanceof List<?> l
                    ? (List<String>) l : null;
            options = new RuntimeApi.TurnOptions(maxSteps, approval, allow);
        }
        return ApiResponse.ok(runtime.startTurn(id, new RuntimeApi.StartTurnCmd(
                clientMsgId, input, options, str(body.get("origin")), str(body.get("originRef")))));
    }

    @GetMapping("/threads/{id}/items")
    public ApiResponse<List<RuntimeApi.ItemView>> items(@PathVariable String id,
                                                        @RequestParam(defaultValue = "0") long afterSeq,
                                                        @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(runtime.listItems(id, afterSeq, limit));
    }

    @GetMapping("/turns/{id}")
    public ApiResponse<RuntimeApi.TurnView> getTurn(@PathVariable String id) {
        return ApiResponse.ok(runtime.getTurn(id));
    }

    @PostMapping("/turns/{id}/interrupt")
    @Audited(action = "runtime.turn.interrupt", resourceType = "turn", resourceIdArg = "#id")
    public ApiResponse<Void> interrupt(@PathVariable String id) {
        runtime.interrupt(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/turns/{id}/steer")
    @Audited(action = "runtime.turn.steer", resourceType = "turn", resourceIdArg = "#id")
    public ApiResponse<Void> steer(@PathVariable String id, @RequestBody Map<String, String> body) {
        runtime.steer(id, body.getOrDefault("text", ""));
        return ApiResponse.ok(null);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
