package com.tioka.runtime.api;

import com.tioka.framework.error.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 旧会话 REST（经 {@link com.tioka.runtime.kernel.application.LegacySessionAdapter}）。
 * 流式请用 {@code GET /api/runtime/threads/{id}/events}。
 */
@RestController
@RequestMapping("/api/runtime")
public class RuntimeController {
    private final RuntimeSessionApi runtime;

    public RuntimeController(RuntimeSessionApi runtime) {
        this.runtime = runtime;
    }

    @PostMapping("/sessions")
    public ApiResponse<RuntimeSessionApi.SessionView> create(@RequestBody RuntimeSessionApi.CreateSessionRequest req) {
        return ApiResponse.ok(runtime.create(req));
    }

    @PostMapping("/sessions/{id}/append")
    public ApiResponse<RuntimeSessionApi.TurnView> append(@PathVariable String id,
                                                          @RequestBody RuntimeSessionApi.AppendRequest req) {
        return ApiResponse.ok(runtime.append(id, req));
    }

    @PostMapping("/sessions/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable String id) {
        runtime.cancel(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/sessions/{id}/steer")
    public ApiResponse<Void> steer(@PathVariable String id, @RequestBody Map<String, String> body) {
        runtime.steer(id, body.getOrDefault("text", ""));
        return ApiResponse.ok(null);
    }
}
