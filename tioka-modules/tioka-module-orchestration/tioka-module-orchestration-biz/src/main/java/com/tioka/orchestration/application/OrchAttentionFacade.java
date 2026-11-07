package com.tioka.orchestration.application;

import com.tioka.framework.error.NotImplemented;
import com.tioka.orchestration.api.OrchAttentionApi;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 人审面桩：orch_attention 已随 V132 删除，ORC-04 迁到 runtime_approval。
 */
@Service
public class OrchAttentionFacade implements OrchAttentionApi {

    @Override
    public AttentionView openAttention(String runId, String title, Map<String, Object> card) {
        throw NotImplemented.of("OrchAttentionApi.openAttention", "ORC-04");
    }

    @Override
    public AttentionView answer(String attentionId, Map<String, Object> answer) {
        throw NotImplemented.of("OrchAttentionApi.answer", "ORC-04");
    }

    @Override
    public List<AttentionView> listOpenAttentions() {
        throw NotImplemented.of("OrchAttentionApi.listOpenAttentions", "ORC-04");
    }
}
