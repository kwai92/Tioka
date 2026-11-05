package com.tioka.runtime.application;

import com.tioka.model.api.FailoverEvent;
import com.tioka.runtime.infrastructure.CostEventRepository;
import com.tioka.runtime.infrastructure.persistence.CostEventDO;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class CostTimelineService {
    private final CostEventRepository costs;

    public CostTimelineService(CostEventRepository costs) {
        this.costs = costs;
    }

    @EventListener
    public void onUsage(RuntimeSessionService.UsageReportedEvent event) {
        String reason = event.routeReason() == null || event.routeReason().isBlank() ? "ok" : event.routeReason();
        record(event.sessionId(), event.modelRef(), event.promptTokens(), event.completionTokens(), reason);
    }

    @EventListener
    public void onFailover(FailoverEvent event) {
        record(event.sessionId(), event.modelRef(), 0, 0, "failover");
    }

    public void record(String sessionId, String modelRef, int promptTokens, int completionTokens, String reason) {
        CostEventDO row = new CostEventDO();
        row.setSessionId(sessionId);
        row.setAtTs(Instant.now().toString());
        row.setModelRef(modelRef);
        row.setPromptTokens(promptTokens);
        row.setCompletionTokens(completionTokens);
        row.setReason(reason);
        costs.insert(row);
    }

    public List<CostEvent> timeline(String sessionId) {
        return costs.findBySessionId(sessionId).stream()
                .map(r -> new CostEvent(r.getAtTs(), r.getModelRef(), r.getPromptTokens(), r.getCompletionTokens(),
                        r.getReason(), r.getReason()))
                .toList();
    }

    /** routeReason 与 reason 同值，供侧栏/API 兼容。 */
    public record CostEvent(String at, String modelRef, int promptTokens, int completionTokens,
                            String reason, String routeReason) {
        public CostEvent(String at, String modelRef, int promptTokens, int completionTokens, String reason) {
            this(at, modelRef, promptTokens, completionTokens, reason, reason);
        }
    }
}
