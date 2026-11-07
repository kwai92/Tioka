package com.tioka.orchestration.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.orchestration.api.OrchAttentionApi;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * V132 后人审表已删；过渡 API 显式失败，待 ORC-04 挂 runtime_approval。
 */
@Service
public class OrchAttentionFacade implements OrchAttentionApi {

    private static BizException gone() {
        return new BizException(ErrorCode.NOT_IMPLEMENTED,
                "V132 已移除 orch_attention；待 ORC-04 → runtime_approval");
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
    public List<AttentionView> listOpenAttentions() {
        throw gone();
    }
}
