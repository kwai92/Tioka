package com.tioka.runtime.agui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.AccessLevel;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.ResourceGuard;
import com.tioka.runtime.agui.dto.AgUiRunAgentInput;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.RuntimeEventType;
import com.tioka.runtime.config.KernelProperties;
import com.tioka.runtime.kernel.events.EventId;
import com.tioka.runtime.kernel.events.EventStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AG-UI run：鉴权 → thread 映射 → startTurn → 订阅内部事件流并映射写出（不改事件写入路径）。
 */
@Service
public class AgUiRunService {
    private static final Logger log = LoggerFactory.getLogger(AgUiRunService.class);

    private final AgUiThreadResolver threads;
    private final RuntimeApi runtime;
    private final AgentSnapshotApi agents;
    private final ResourceGuard resourceGuard;
    private final PrincipalHolder principals;
    private final EventStream streams;
    private final AgUiEventMapper mapper;
    private final ObjectMapper objectMapper;
    private final KernelProperties props;
    private final ExecutorService pump = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "agui-run");
        t.setDaemon(true);
        return t;
    });

    public AgUiRunService(AgUiThreadResolver threads,
                          RuntimeApi runtime,
                          AgentSnapshotApi agents,
                          ResourceGuard resourceGuard,
                          PrincipalHolder principals,
                          EventStream streams,
                          AgUiEventMapper mapper,
                          ObjectMapper objectMapper,
                          KernelProperties props) {
        this.threads = threads;
        this.runtime = runtime;
        this.agents = agents;
        this.resourceGuard = resourceGuard;
        this.principals = principals;
        this.streams = streams;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    public SseEmitter run(String agentVersionId, AgUiRunAgentInput input) {
        if (input == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "RunAgentInput 必填");
        }
        if (input.runId() == null || input.runId().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "runId 必填");
        }
        AgentSnapshotApi.AgentSnapshot snap = agents.get(agentVersionId);
        resourceGuard.check("agent", snap.agentId(), AccessLevel.USE);

        SseEmitter emitter = new SseEmitter(props.getEvents().getSseTimeout().toMillis());
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(e -> closed.set(true));

        if (input.tools() != null && !input.tools().isEmpty()) {
            sendAgUi(emitter, mapper.clientToolsUnsupported());
            emitter.complete();
            return emitter;
        }

        String userText = lastUserText(input);
        if (userText == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "messages 需含 role=user");
        }

        String aguiThreadId = input.threadId();
        String internalThreadId = threads.resolveOrCreate(aguiThreadId, agentVersionId);
        String cursor = streams.latestEntryId(internalThreadId);

        RuntimeApi.TurnHandle handle = runtime.startTurn(internalThreadId, new RuntimeApi.StartTurnCmd(
                input.runId(),
                List.of(RuntimeApi.InputItem.text(userText)),
                null,
                "chat",
                null));

        Principal principal = principals.current();
        pump.execute(() -> principals.runAs(principal, () -> pumpEvents(emitter, closed,
                internalThreadId, aguiThreadId, input.runId(), handle.turnId(), cursor)));
        return emitter;
    }

    private void pumpEvents(SseEmitter emitter, AtomicBoolean closed,
                            String internalThreadId, String aguiThreadId, String runId,
                            String turnId, String afterEntryId) {
        AgUiMappingState state = new AgUiMappingState();
        String last = afterEntryId == null || afterEntryId.isBlank() ? "0-0" : afterEntryId;
        EventId sent = EventId.parse("");
        long deadline = System.currentTimeMillis() + props.getEvents().getSseTimeout().toMillis();
        try {
            while (!closed.get() && System.currentTimeMillis() < deadline) {
                String latest = streams.latestEntryId(internalThreadId);
                List<EventStream.StreamEvent> batch =
                        streams.rangeExclusiveAfter(internalThreadId, last, latest);
                if (batch.isEmpty()) {
                    RuntimeApi.TurnView turn = runtime.getTurn(turnId);
                    if (turn.status().isTerminal()) {
                        // 终态事件可能已在流中；再扫一次后补发映射的终态（若尚未发出）
                        batch = streams.rangeExclusiveAfter(internalThreadId, last, streams.latestEntryId(internalThreadId));
                        boolean terminalEmitted = false;
                        for (EventStream.StreamEvent ev : batch) {
                            last = ev.entryId();
                            terminalEmitted |= emitMapped(emitter, state, aguiThreadId, runId, ev, sent);
                            if (ev.eventId() != null && !ev.eventId().isBlank()) {
                                sent = EventId.parse(ev.eventId());
                            }
                        }
                        if (!terminalEmitted) {
                            RuntimeEventType t = switch (turn.status()) {
                                case COMPLETED -> RuntimeEventType.TURN_COMPLETED;
                                case CANCELLED -> RuntimeEventType.TURN_CANCELLED;
                                default -> RuntimeEventType.TURN_FAILED;
                            };
                            ObjectNode synthetic = objectMapper.createObjectNode();
                            synthetic.put("type", t.wire());
                            synthetic.put("threadId", internalThreadId);
                            synthetic.put("turnId", turnId);
                            synthetic.putObject("turn").put("status", turn.status().name());
                            if (turn.errorMessage() != null) {
                                synthetic.put("message", turn.errorMessage());
                            }
                            for (ObjectNode agui : mapper.map(synthetic, state, aguiThreadId, runId)) {
                                sendAgUi(emitter, agui);
                            }
                        }
                        emitter.complete();
                        return;
                    }
                    Thread.sleep(40);
                    continue;
                }
                for (EventStream.StreamEvent ev : batch) {
                    last = ev.entryId();
                    boolean terminal = emitMapped(emitter, state, aguiThreadId, runId, ev, sent);
                    if (ev.eventId() != null && !ev.eventId().isBlank()) {
                        EventId eid = EventId.parse(ev.eventId());
                        if (eid.compareTo(sent) > 0) {
                            sent = eid;
                        }
                    }
                    if (terminal) {
                        emitter.complete();
                        return;
                    }
                }
            }
            if (!closed.get()) {
                emitter.complete();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.completeWithError(e);
        } catch (Exception e) {
            log.debug("agui pump: {}", e.toString());
            emitter.completeWithError(e);
        }
    }

    /** @return 是否已写出终态（RUN_FINISHED / RUN_ERROR） */
    private boolean emitMapped(SseEmitter emitter, AgUiMappingState state,
                               String aguiThreadId, String runId,
                               EventStream.StreamEvent ev, EventId sent) throws IOException {
        if (ev.eventId() != null && !ev.eventId().isBlank()) {
            EventId eid = EventId.parse(ev.eventId());
            if (eid.compareTo(sent) <= 0 && ev.type() != null && !ev.type().startsWith("turn.")) {
                return false;
            }
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(ev.dataJson() == null ? "{}" : ev.dataJson());
        } catch (Exception e) {
            return false;
        }
        if (!node.has("type") && ev.type() != null) {
            ((ObjectNode) node).put("type", ev.type());
        }
        boolean terminal = false;
        for (ObjectNode agui : mapper.map(node, state, aguiThreadId, runId)) {
            sendAgUi(emitter, agui);
            String t = agui.path("type").asText();
            if (AgUiEventType.RUN_FINISHED.equals(t) || AgUiEventType.RUN_ERROR.equals(t)) {
                terminal = true;
            }
        }
        return terminal;
    }

    private void sendAgUi(SseEmitter emitter, ObjectNode event) {
        try {
            // AG-UI HTTP+SSE：每帧 data 为完整事件 JSON（type 在体内）
            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(event)));
        } catch (IOException | IllegalStateException e) {
            emitter.complete();
        }
    }

    private static String lastUserText(AgUiRunAgentInput input) {
        if (input.messages() == null || input.messages().isEmpty()) {
            return null;
        }
        for (int i = input.messages().size() - 1; i >= 0; i--) {
            AgUiRunAgentInput.AgUiMessage m = input.messages().get(i);
            if (m != null && "user".equals(m.role()) && m.content() != null && !m.content().isBlank()) {
                return m.content();
            }
        }
        return null;
    }
}
