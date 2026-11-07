package com.tioka.orchestration.api;

import java.util.List;
import java.util.Map;

/**
 * 编排人审过渡 API（ORC-03 前 Chat/Flow/Agent 唯一允许依赖的 Orch 人审面）。
 * 目标：Attention → {@code runtime_approval}（{@code ApprovalSubjectType.ORCH_RUN}）。
 */
public interface OrchAttentionApi {
    AttentionView openAttention(String runId, String title, Map<String, Object> card);

    AttentionView answer(String attentionId, Map<String, Object> answer);

    List<AttentionView> listOpenAttentions();

    record AttentionView(String id, String runId, String status, String title,
                         Map<String, Object> card, Map<String, Object> answer) {}
}
