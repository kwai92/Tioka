
package com.tioka.runtime.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.runtime.application.RuntimeSessionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@RestController
@RequestMapping("/api/runtime")
public class RuntimeController {
    private final RuntimeSessionApi runtime;
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

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

    @GetMapping(path = "/sessions/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> emitters.getOrDefault(id, new CopyOnWriteArrayList<>()).remove(emitter));
        emitter.onTimeout(() -> emitters.getOrDefault(id, new CopyOnWriteArrayList<>()).remove(emitter));
        try {
            emitter.send(SseEmitter.event().name("ready").data("ok"));
        } catch (IOException ignored) {
        }
        return emitter;
    }

    @org.springframework.context.event.EventListener
    public void onPart(RuntimeSessionService.PartAppendedEvent event) {
        var list = emitters.get(event.sessionId());
        if (list == null) return;
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("part").data(event.part()));
            } catch (IOException e) {
                emitter.complete();
            }
        }
    }
}
