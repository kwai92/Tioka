package com.tioka.governance.api.config;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * GOV-12 实现档验收骨架（ADR-041 §2 / M3-00）。
 * 本契约 PR 仅冻结 API；实现与真实 IT 留给后续提交。
 */
@Disabled("GOV-12 契约先行：实现未接线")
class ConfigVersionLifecycleIT {

    @Test
    void publishThenRollbackRestoresProjection() {
        // 实现档：saveDraft → validate → publish → rollback
    }

    @Test
    void refreshAcrossInstancesAfterPublish() {
        // 实现档：ConfigRefreshAcrossInstancesIT
    }
}
