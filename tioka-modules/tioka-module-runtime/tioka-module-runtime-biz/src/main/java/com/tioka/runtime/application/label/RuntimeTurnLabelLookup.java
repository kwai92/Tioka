package com.tioka.runtime.application.label;

import com.tioka.framework.label.DataLabel;
import com.tioka.model.api.label.TurnLabelLookup;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** {@link TurnLabelLookup} 实现（DAT-03）：读 {@code runtime_thread.data_label}（V164）。 */
@Component
public class RuntimeTurnLabelLookup implements TurnLabelLookup {

    private final ThreadLabelStore store;

    public RuntimeTurnLabelLookup(ThreadLabelStore store) {
        this.store = store;
    }

    @Override
    public Optional<DataLabel> labelOf(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return Optional.empty();
        }
        DataLabel parsed = TurnLabelTracker.parse(store.current(threadId));
        return Optional.ofNullable(parsed);
    }
}
