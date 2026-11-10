package com.tioka.runtime.infrastructure.label;

import com.tioka.framework.label.DataLabel;
import com.tioka.runtime.application.label.ThreadLabelStore;
import com.tioka.runtime.infrastructure.mapper.RuntimeThreadMapper;
import com.tioka.runtime.infrastructure.persistence.RuntimeThreadDO;
import org.springframework.stereotype.Repository;

/** {@code runtime_thread.data_label} 存取实现（DAT-02 / V164）。 */
@Repository
public class RuntimeThreadLabelStore implements ThreadLabelStore {

    private final RuntimeThreadMapper threads;

    public RuntimeThreadLabelStore(RuntimeThreadMapper threads) {
        this.threads = threads;
    }

    @Override
    public String current(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return null;
        }
        RuntimeThreadDO row = threads.selectById(threadId);
        return row == null ? null : row.getDataLabel();
    }

    @Override
    public void update(String threadId, DataLabel label) {
        RuntimeThreadDO patch = new RuntimeThreadDO();
        patch.setId(threadId);
        patch.setDataLabel(label.name());
        threads.updateById(patch);
    }
}
