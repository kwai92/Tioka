package com.tioka.runtime.api.mq;

/**
 * MQ Topic/Tag 与跨域事件名常量（禁止在 biz 内硬编码字符串）。
 *
 * <p>权威 tag：{@code thread.*} / {@code item.*}（47-GL Runtime；ADR-016）。
 * 旧 {@code TAG_SESSION_*} / {@code TAG_PART_*} 仅作编译兼容，已 alias 新字符串。
 * 跨域事件名见 M2-01 ORC-02 / {@code docs/contracts/events}。
 */
public final class RuntimeMqTopics {

    public static final String TOPIC = "tioka-runtime";

    public static final String TAG_THREAD_CREATED = "thread.created";
    public static final String TAG_THREAD_COMPLETED = "thread.completed";
    public static final String TAG_TURN_STARTED = "turn.started";
    public static final String TAG_TURN_COMPLETED = "turn.completed";
    public static final String TAG_ITEM_APPENDED = "item.appended";
    public static final String TAG_TOOL_INVOKED = "tool.invoked";
    public static final String TAG_APPROVAL_REQUESTED = "approval.requested";
    public static final String TAG_SUBAGENT_FINISHED = "subagent.finished";
    public static final String TAG_USAGE_REPORTED = "usage.reported";
    public static final String TAG_SANDBOX_DESTROYED = "sandbox.destroyed";

    public static final String TURN_FINISHED = "tioka.runtime.turn_finished.v1";
    public static final String TURN_WAITING = "tioka.runtime.turn_waiting.v1";
    public static final String APPROVAL_REQUESTED = "tioka.runtime.approval_requested.v1";
    public static final String APPROVAL_DECIDED = "tioka.runtime.approval_decided.v1";
    public static final String CHILD_FINISHED = "tioka.runtime.child_finished.v1";

    /** @deprecated 使用 {@link #TAG_THREAD_CREATED} */
    @Deprecated
    public static final String TAG_SESSION_CREATED = TAG_THREAD_CREATED;
    /** @deprecated 使用 {@link #TAG_THREAD_COMPLETED} */
    @Deprecated
    public static final String TAG_SESSION_COMPLETED = TAG_THREAD_COMPLETED;
    /** @deprecated 使用 {@link #TAG_ITEM_APPENDED} */
    @Deprecated
    public static final String TAG_PART_APPENDED = TAG_ITEM_APPENDED;

    private RuntimeMqTopics() {
    }
}
