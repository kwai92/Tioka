package com.tioka.orchestration.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.orchestration.api.OrchAttentionApi;
import com.tioka.orchestration.attention.OrchAttentionService;
import com.tioka.orchestration.api.engine.Receipt;
import com.tioka.orchestration.infrastructure.OrchEngineRepository;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import com.tioka.runtime.api.approval.ApprovalView;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 人审面：委托 ORC-04 {@link OrchAttentionService}（V132 后走 runtime_approval）。
 */
@Service
public class OrchAttentionFacade implements OrchAttentionApi {

    private final OrchAttentionService attention;
    private final OrchEngineRepository engine;

    public OrchAttentionFacade(OrchAttentionService attention, OrchEngineRepository engine) {
        this.attention = attention;
        this.engine = engine;
    }

    @Override
    public AttentionView openAttention(String runId, String title, Map<String, Object> card) {
        OrchRunDO run = engine.findRun(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Run 不存在");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> form = card != null && card.get("form") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();
        @SuppressWarnings("unchecked")
        Map<String, List<String>> assignee = card != null && card.get("assignee") instanceof Map<?, ?> m
                ? (Map<String, List<String>>) m : Map.of();
        String key = card != null && card.get("key") != null
                ? String.valueOf(card.get("key")) : "manual:" + runId;
        Receipt.Ask ask = new Receipt.Ask(key, title, form, assignee, Duration.ofHours(24), null);
        ApprovalView view = attention.openFromAsk(run, ask);
        return new AttentionView(view.id(), runId, "PENDING", title, view.card(), Map.of());
    }

    @Override
    public AttentionView answer(String attentionId, Map<String, Object> answer) {
        throw new BizException(ErrorCode.BAD_REQUEST,
                "请走 RuntimeApi.decideApproval / 审批台提交，attentionId=" + attentionId);
    }

    @Override
    public List<AttentionView> listOpenAttentions() {
        return List.of();
    }
}
