package com.tioka.runtime.api;

import com.tioka.framework.security.RequirePermission;
import com.tioka.runtime.kernel.events.ThreadEventsService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/runtime/threads")
@RequirePermission("chat:use")
public class ThreadEventsController {
    private final ThreadEventsService events;

    public ThreadEventsController(ThreadEventsService events) {
        this.events = events;
    }

    @GetMapping(path = "/{threadId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String threadId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventIdHeader,
                             @RequestParam(value = "after", required = false) String after) {
        String last = (lastEventIdHeader != null && !lastEventIdHeader.isBlank())
                ? lastEventIdHeader
                : after;
        return events.open(threadId, last);
    }
}
