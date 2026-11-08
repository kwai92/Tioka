package com.tioka.system.api.obs;

/** 空实现：台账未落地前丢弃记录。 */
public final class NoopExtInteractionRecorder implements ExtInteractionRecorder {

    @Override
    public void record(ExtInteractionRecord record) {
        // no-op
    }
}
