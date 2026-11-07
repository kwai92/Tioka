package com.tioka.orchestration.application;

import com.tioka.orchestration.api.OrchApi;
import com.tioka.orchestration.api.OrchAttentionApi;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 将 Deprecated {@link OrchApi} 人审面适配为 {@link OrchAttentionApi}，
 * 供 Chat/Flow/Agent 依赖（禁止直依 Deprecated OrchApi）。
 */
@Service
public class OrchAttentionFacade implements OrchAttentionApi {
    private final OrchService orch;

    public OrchAttentionFacade(OrchService orch) {
        this.orch = orch;
    }

    @Override
    public AttentionView openAttention(String runId, String title, Map<String, Object> card) {
        return map(orch.openAttention(runId, title, card));
    }

    @Override
    public AttentionView answer(String attentionId, Map<String, Object> answer) {
        return map(orch.answer(attentionId, answer));
    }

    @Override
    public List<AttentionView> listOpenAttentions() {
        return orch.openAttentions().stream().map(OrchAttentionFacade::map).toList();
    }

    private static AttentionView map(OrchApi.AttentionView v) {
        return new AttentionView(v.id(), v.runId(), v.status(), v.title(), v.card(), v.answer());
    }
}
