package com.tioka.orchestration.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.orchestration.api.OrchApi;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Deprecated M0 OrchApi：V132 已 DROP claim/attention/why 表。
 * 调用方应迁 {@link com.tioka.orchestration.api.engine.OrchApi} / ORC-03。
 */
@Service
@Deprecated(since = "2026-11-07", forRemoval = true)
public class OrchService implements OrchApi {

    private static BizException gone() {
        return new BizException(ErrorCode.NOT_IMPLEMENTED,
                "V132 已移除 M0 claim/attention；待 ORC-03/04");
    }

    @Override
    public String ingestEvent(String eventType, String idempotencyKey, Map<String, Object> payload) {
        throw gone();
    }

    @Override
    public RunView claim(String workerId) {
        throw gone();
    }

    @Override
    public void heartbeat(String runId, String workerId) {
        throw gone();
    }

    @Override
    public AttentionView openAttention(String runId, String title, Map<String, Object> card) {
        throw gone();
    }

    @Override
    public AttentionView answer(String attentionId, Map<String, Object> answer) {
        throw gone();
    }

    @Override
    public List<WhyStep> why(String runId) {
        throw gone();
    }

    public List<AttentionView> openAttentions() {
        throw gone();
    }
}
