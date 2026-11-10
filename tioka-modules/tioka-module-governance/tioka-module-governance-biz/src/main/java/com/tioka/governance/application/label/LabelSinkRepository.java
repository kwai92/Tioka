package com.tioka.governance.application.label;

import com.tioka.governance.api.label.SinkKind;

import java.util.Optional;

/**
 * 去向许可规则存取（V164 gov_label_sink）。实现在 governance-biz infrastructure；
 * 离线测试用内存实现。
 */
public interface LabelSinkRepository {

    Optional<LabelSinkRule> find(String workspaceId, SinkKind kind, String ref);

    void save(LabelSinkRule rule);

    /** 规则（ADR-040 数据模型 gov_label_sink 行）。 */
    record LabelSinkRule(
            String workspaceId,
            SinkKind kind,
            String ref,
            String maxLabel,
            String onExceed) {}
}
